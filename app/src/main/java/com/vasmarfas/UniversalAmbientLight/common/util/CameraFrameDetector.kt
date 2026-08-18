package com.vasmarfas.UniversalAmbientLight.common.util

import java.util.Locale
import kotlin.math.abs

/**
 * Ищет экран телевизора в кадре камеры, чтобы четырёхугольник захвата можно было выставить
 * автоматически, а не растаскивать углы пальцами (issue #39).
 *
 * Вызывающий код накапливает сетки яркости подряд, снятые с кадров камеры, и спрашивает
 * [detect]; тот отдаёт углы в нормализованных 0..1 — той же системе координат, в которой
 * хранится настройка углов.
 *
 * Решают два признака:
 *  - яркость отделяет светящуюся панель от комнаты вокруг. Порог берётся методом Оцу,
 *    поэтому и тёмная комната, и освещённая обходятся без зашитого в код числа;
 *  - доля меняющихся ячеек внутри области отличает экран от лампы или окна: у телевизора
 *    с видео меняется почти вся площадь, у лампы — ничего, у бликующего окна — край.
 *
 * Поодиночке ни один не работает: по одной яркости выигрывает лампа, по одной изменчивости
 * теряется телевизор, замерший на стоп-кадре.
 *
 * Точность углов держится на двух решениях: яркость ячейки берётся вторым по величине
 * замером (одиночный шумовой всплеск не считается панелью), а стороны четырёхугольника
 * подгоняются прямыми по всем граничным ячейкам, а не по четырём крайним точкам.
 *
 * Зависимостей от Android нет: вся геометрия проверяется юнит-тестами.
 */
class CameraFrameDetector(
    val cols: Int = DEFAULT_COLS,
    val rows: Int = DEFAULT_ROWS,
) {

    val cellCount = cols * rows

    /** Наибольший и следующий за ним замеры яркости ячейки. */
    private val mHigh = IntArray(cellCount)
    private val mSecond = IntArray(cellCount)

    /** Сколько раз ячейка заметно изменилась между соседними кадрами. */
    private val mChanges = IntArray(cellCount)

    private val mPrevious = IntArray(cellCount)

    var frameCount = 0
        private set

    fun reset() {
        mHigh.fill(0)
        mSecond.fill(0)
        mChanges.fill(0)
        mPrevious.fill(0)
        frameCount = 0
    }

    /**
     * Принимает одну сетку яркости: [cols] × [rows] построчно, значения 0..255.
     * Сетки короче [cellCount] игнорируются.
     */
    fun addFrame(luma: IntArray) {
        if (luma.size < cellCount) return

        for (i in 0 until cellCount) {
            val value = luma[i]
            if (frameCount > 0 && abs(value - mPrevious[i]) >= CELL_CHANGE_LEVEL) mChanges[i]++
            when {
                value > mHigh[i] -> {
                    mSecond[i] = mHigh[i]
                    mHigh[i] = value
                }

                value > mSecond[i] -> mSecond[i] = value
            }
            mPrevious[i] = value
        }
        frameCount++
    }

    /** Разбирает всё накопленное. Вызывается один раз на окно замеров. */
    fun detect(): Detection {
        if (frameCount < 1) return Detection.rejected(Code.NO_FRAMES, "no frames sampled")

        // Второй по величине замер вместо максимума: один шумный кадр не должен назначать
        // ячейку светящейся. На единственном кадре второго нет, берём что есть.
        val brightness = if (frameCount >= 2) mSecond else mHigh

        // Оцу возвращает нижний край впадины между двумя классами яркости, то есть сам порог
        // стоит чуть выше комнаты, а не рядом с экраном. Поэтому проверки ниже смотрят на
        // средние по классам, а не на значение порога.
        val threshold = otsuThreshold(brightness)

        var brightSum = 0L
        var brightCount = 0
        var darkSum = 0L
        for (value in brightness) {
            if (value > threshold) {
                brightSum += value
                brightCount++
            } else {
                darkSum += value
            }
        }
        if (brightCount == 0) return Detection.rejected(Code.TOO_DARK, "no cells above threshold")

        // Оцу всегда что-нибудь да разделит; три проверки ниже отличают светящуюся панель в
        // тёмной комнате от двух оттенков шума в комнате без телевизора.
        val brightMean = (brightSum / brightCount).toInt()
        if (brightMean < MIN_SCREEN_LUMA) {
            return Detection.rejected(
                Code.TOO_DARK, "frame too dark (brightest region $brightMean)"
            )
        }

        val darkCount = cellCount - brightCount
        if (darkCount == 0) {
            return Detection.rejected(Code.NO_SCREEN, "no darker surround around the screen")
        }

        val darkMean = (darkSum / darkCount).toInt()
        if (brightMean - darkMean < MIN_CONTRAST) {
            return Detection.rejected(
                Code.NO_SCREEN, "no clear screen edge (contrast ${brightMean - darkMean})"
            )
        }

        val labels = IntArray(cellCount) { UNLABELED }
        val stack = IntArray(cellCount)
        val regions = ArrayList<Region>()
        for (cell in 0 until cellCount) {
            if (labels[cell] != UNLABELED || brightness[cell] <= threshold) continue
            regions.add(floodFill(cell, regions.size, brightness, threshold, labels, stack))
        }

        val minCells = (cellCount * MIN_AREA_FRACTION).toInt().coerceAtLeast(4)
        val maxCells = (cellCount * MAX_AREA_FRACTION).toInt()

        // Порог для ядра заметно ниже итогового: на тёмной сцене ярким оказывается лишь
        // кусок панели (титры, окно в кадре), а до её краёв область дорастает ниже. Отсев
        // по площади применяется к тому, что выросло, иначе фильм с тёмной картинкой
        // отбраковывался бы целиком.
        val minCore = (cellCount * MIN_CORE_FRACTION).toInt().coerceAtLeast(4)
        val candidates = regions.filter { it.count in minCore..maxCells }
        if (candidates.isEmpty()) {
            val largest = regions.maxOfOrNull { it.count } ?: 0
            return Detection.rejected(
                Code.NO_SCREEN, "no region of usable size (largest $largest cells)"
            )
        }

        // Область, меняющаяся почти целиком, — это экран с видео. Берём среди таких самую
        // большую: у бликующего окна меняется край, у телевизора — вся панель. Если не
        // меняется ничто (пауза, статичное меню), решает одна площадь.
        val playing = candidates.filter { it.changedFraction >= VIDEO_COVERAGE }
        val winner = (if (playing.isNotEmpty()) playing else candidates).maxBy { it.count }

        val grown = growToPanel(winner, labels, brightness, threshold, maxCells)
        if (winner.count < minCells) {
            return Detection.rejected(
                Code.NO_SCREEN, "screen area too small (${winner.count} cells)"
            )
        }

        return cornersOf(winner, grown)
    }

    /**
     * Дорастивает найденное яркое ядро до всей панели по второму, низкому порогу.
     *
     * Порог Оцу делит кадр надвое, и «ярким» оказывается самое светлое пятно: на экране это
     * запросто белый документ или светлая сцена, а тёмный интерфейс по краям уезжает в один
     * класс с комнатой. Второй порог считается тем же Оцу, но уже по одному тёмному классу —
     * он отделяет тусклые части экрана от комнаты. Это те же два порога, что у Canny.
     *
     * Рост разрешён только в неразмеченные ячейки: соседняя лампа, у которой своя метка,
     * к панели не приклеится. Если область раздулась сверх меры, рост откатывается —
     * значит, порог утёк в комнату.
     *
     * @return массив меток, по которому строить углы: с ростом или исходный
     */
    private fun growToPanel(
        winner: Region,
        labels: IntArray,
        brightness: IntArray,
        threshold: Int,
        maxCells: Int,
    ): IntArray {
        var darkCount = 0
        for (value in brightness) if (value <= threshold) darkCount++
        if (darkCount == 0) return labels

        val darkValues = IntArray(darkCount)
        var index = 0
        for (value in brightness) if (value <= threshold) darkValues[index++] = value
        val lowThreshold = otsuThreshold(darkValues)

        var dimSum = 0L
        var dimCount = 0
        var roomSum = 0L
        var roomCount = 0
        for (value in darkValues) {
            if (value > lowThreshold) {
                dimSum += value
                dimCount++
            } else {
                roomSum += value
                roomCount++
            }
        }
        // Тусклой части экрана может и не быть — тогда расти некуда, а порог, посчитанный по
        // одному шуму комнаты, увёл бы область на стену.
        if (dimCount == 0 || roomCount == 0) return labels
        if (dimSum / dimCount - roomSum / roomCount < MIN_GROWTH_CONTRAST) return labels

        val grown = labels.copyOf()
        val stack = IntArray(cellCount)
        var top = 0
        for (cell in 0 until cellCount) {
            if (grown[cell] == winner.label) stack[top++] = cell
        }
        var count = top
        // Предел один — доля кадра. Ограничение «не больше стольких-то ядер» отсекало как
        // раз нужный случай: на тёмной сцене ярким бывает пара процентов панели, и до её
        // краёв области расти в десятки раз. Правдоподобие выросшей области проверяют
        // размер, форма и подгонка сторон ниже, а не множитель.
        val limit = maxCells

        while (top > 0) {
            val cell = stack[--top]
            val cx = cell % cols
            val cy = cell / cols
            if (cx > 0) {
                top = pushGrown(cell - 1, winner.label, brightness, lowThreshold, grown, stack, top)
            }
            if (cx < cols - 1) {
                top = pushGrown(cell + 1, winner.label, brightness, lowThreshold, grown, stack, top)
            }
            if (cy > 0) {
                top = pushGrown(
                    cell - cols, winner.label, brightness, lowThreshold, grown, stack, top
                )
            }
            if (cy < rows - 1) {
                top = pushGrown(
                    cell + cols, winner.label, brightness, lowThreshold, grown, stack, top
                )
            }
            count = maxOf(count, top)
            if (count > limit) return labels
        }

        var total = 0
        for (cell in 0 until cellCount) if (grown[cell] == winner.label) total++
        if (total > limit) return labels

        winner.count = total
        return grown
    }

    /** Шаг роста: занимаем только неразмеченные ячейки, чужие области не поглощаем. */
    private fun pushGrown(
        cell: Int,
        label: Int,
        brightness: IntArray,
        lowThreshold: Int,
        labels: IntArray,
        stack: IntArray,
        top: Int,
    ): Int {
        if (labels[cell] != UNLABELED || brightness[cell] <= lowThreshold) return top
        labels[cell] = label
        stack[top] = cell
        return top + 1
    }

    /**
     * Строит четырёхугольник победившей области. Стороны подгоняются прямыми по краям
     * области: каждая строка даёт по одной точке левому и правому краю, каждый столбец —
     * верхнему и нижнему. Углы — пересечения соседних прямых.
     *
     * Так на угол работают все граничные ячейки, а не четыре крайние, и одна выпавшая
     * ячейка (блик, тёмная полоса по краю кадра) уже не сдвигает угол на всю свою ширину.
     */
    private fun cornersOf(winner: Region, labels: IntArray): Detection {
        val rowMinX = IntArray(rows) { NONE }
        val rowMaxX = IntArray(rows) { NONE }
        val colMinY = IntArray(cols) { NONE }
        val colMaxY = IntArray(cols) { NONE }

        // Грубый четырёхугольник по диагональным крайним точкам: по нему считается,
        // насколько плотно область его заполняет, и он следует за наклоном камеры.
        var minSum = Int.MAX_VALUE
        var maxSum = Int.MIN_VALUE
        var minDiff = Int.MAX_VALUE
        var maxDiff = Int.MIN_VALUE
        val coarseX = IntArray(4)
        val coarseY = IntArray(4)

        for (cell in 0 until cellCount) {
            if (labels[cell] != winner.label) continue
            val x = cell % cols
            val y = cell / cols
            if (rowMinX[y] == NONE || x < rowMinX[y]) rowMinX[y] = x
            if (rowMaxX[y] == NONE || x > rowMaxX[y]) rowMaxX[y] = x
            if (colMinY[x] == NONE || y < colMinY[x]) colMinY[x] = y
            if (colMaxY[x] == NONE || y > colMaxY[x]) colMaxY[x] = y

            val sum = x + y
            if (sum < minSum) {
                minSum = sum; coarseX[0] = x; coarseY[0] = y
            }
            if (sum > maxSum) {
                maxSum = sum; coarseX[2] = x; coarseY[2] = y
            }
            val diff = x - y
            if (diff > maxDiff) {
                maxDiff = diff; coarseX[1] = x; coarseY[1] = y
            }
            if (diff < minDiff) {
                minDiff = diff; coarseX[3] = x; coarseY[3] = y
            }
        }

        val firstRow = rowMinX.indexOfFirst { it != NONE }
        val lastRow = rowMinX.indexOfLast { it != NONE }
        val firstCol = colMinY.indexOfFirst { it != NONE }
        val lastCol = colMinY.indexOfLast { it != NONE }
        val spanX = (lastCol - firstCol + 1).toFloat()
        val spanY = (lastRow - firstRow + 1).toFloat()

        if (spanX < cols * MIN_SPAN_FRACTION || spanY < rows * MIN_SPAN_FRACTION) {
            return Detection.rejected(Code.NO_SCREEN, "region too small (${spanX.toInt()}x${spanY.toInt()} cells)")
        }

        // Не проверка на 16:9, а отсев вырожденных полосок: в соотношение попадают и формат
        // самого кадра, и угол, под которым стоит камера.
        val aspect = (spanX / cols) / (spanY / rows)
        if (aspect < MIN_ASPECT || aspect > MAX_ASPECT) {
            return Detection.rejected(Code.NO_SCREEN, "implausible shape (aspect ${format2(aspect)})")
        }

        // Прямоугольник заполняет собственный четырёхугольник, а буква «Г» или две области,
        // соединённые перемычкой, — нет. Считаем по наклонному четырёхугольнику, а не по
        // прямой рамке: иначе повёрнутый на десяток градусов экран не прошёл бы проверку.
        val area = quadArea(coarseX, coarseY)
        if (area <= 0f) return Detection.rejected(Code.NO_SCREEN, "degenerate quad")
        val fill = winner.count / area
        if (fill < MIN_FILL_RATIO) {
            return Detection.rejected(Code.NO_SCREEN, "region not rectangular (fill ${format2(fill)})")
        }

        // У наклонённого четырёхугольника крайние строки упираются не в боковую сторону, а в
        // верхнюю или нижнюю. Боковые прямые подгоняем только по полосе между нижним из двух
        // верхних углов и верхним из двух нижних — там левый и правый края точно боковые.
        // С горизонтальными прямыми то же самое по столбцам.
        val left = fitEdge(rowMinX, maxOf(coarseY[0], coarseY[1]) + 1, minOf(coarseY[2], coarseY[3]) - 1)
        val right = fitEdge(rowMaxX, maxOf(coarseY[0], coarseY[1]) + 1, minOf(coarseY[2], coarseY[3]) - 1)
        val top = fitEdge(colMinY, maxOf(coarseX[0], coarseX[3]) + 1, minOf(coarseX[1], coarseX[2]) - 1)
        val bottom = fitEdge(colMaxY, maxOf(coarseX[0], coarseX[3]) + 1, minOf(coarseX[1], coarseX[2]) - 1)
        if (left == null || right == null || top == null || bottom == null) {
            return Detection.rejected(Code.NO_SCREEN, "edges not straight enough")
        }

        val corners = FloatArray(8)
        val order = arrayOf(left to top, right to top, right to bottom, left to bottom)
        for (i in order.indices) {
            val (vertical, horizontal) = order[i]
            val point = intersect(vertical, horizontal)
                ?: return Detection.rejected(Code.NO_SCREEN, "degenerate quad")

            // Подогнанный угол обязан лежать рядом с грубым. Разошлись — значит, прямые
            // легли не по сторонам области, и точный ответ здесь честнее не давать.
            if (abs(point[0] - coarseX[i]) > cols * MAX_FIT_DRIFT ||
                abs(point[1] - coarseY[i]) > rows * MAX_FIT_DRIFT
            ) {
                return Detection.rejected(Code.NO_SCREEN, "fitted corner drifted off the region")
            }

            corners[i * 2] = ((point[0] + 0.5f) / cols).coerceIn(0f, 1f)
            corners[i * 2 + 1] = ((point[1] + 0.5f) / rows).coerceIn(0f, 1f)
        }
        return Detection.found(corners)
    }

    /**
     * Метод наименьших квадратов по диапазону: `value = slope * index + offset`.
     * Второй проход отбрасывает выбросы — точки, отклонившиеся больше чем вдвое от среднего:
     * один блик или тёмная полоса по краю кадра больше не уводят всю сторону.
     * Возвращает null, если точек для прямой не хватило.
     */
    private fun fitEdge(values: IntArray, from: Int, to: Int): FloatArray? {
        if (from < 0 || to >= values.size || to - from + 1 < MIN_EDGE_POINTS) return null

        var line = leastSquares(values, from, to, null) ?: return null

        var deviation = 0f
        var used = 0
        for (i in from..to) {
            if (values[i] == NONE) continue
            deviation += abs(values[i] - (line[0] * i + line[1]))
            used++
        }
        if (used == 0) return null
        val tolerance = (deviation / used) * OUTLIER_FACTOR + 1f

        line = leastSquares(values, from, to, line to tolerance) ?: line
        return line
    }

    /** Одна итерация подгонки; при переданном [reject] пропускает точки вне допуска. */
    private fun leastSquares(
        values: IntArray,
        from: Int,
        to: Int,
        reject: Pair<FloatArray, Float>?,
    ): FloatArray? {
        var n = 0
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumXX = 0.0
        for (i in from..to) {
            val value = values[i]
            if (value == NONE) continue
            if (reject != null) {
                val (line, tolerance) = reject
                if (abs(value - (line[0] * i + line[1])) > tolerance) continue
            }
            n++
            sumX += i
            sumY += value.toDouble()
            sumXY += i.toDouble() * value
            sumXX += i.toDouble() * i
        }
        if (n < MIN_EDGE_POINTS) return null

        val denominator = n * sumXX - sumX * sumX
        if (abs(denominator) < 1e-6) return null
        val slope = (n * sumXY - sumX * sumY) / denominator
        val offset = (sumY - slope * sumX) / n
        return floatArrayOf(slope.toFloat(), offset.toFloat())
    }

    /**
     * Пересечение боковой прямой `x = a·y + b` и горизонтальной `y = c·x + d`.
     * Возвращает null, когда прямые почти параллельны и точка пересечения ничего не значит.
     */
    private fun intersect(vertical: FloatArray, horizontal: FloatArray): FloatArray? {
        val a = vertical[0]
        val b = vertical[1]
        val c = horizontal[0]
        val d = horizontal[1]
        val denominator = 1f - a * c
        if (abs(denominator) < 1e-4f) return null
        val x = (a * d + b) / denominator
        val y = c * x + d
        return floatArrayOf(x, y)
    }

    private fun floodFill(
        start: Int,
        label: Int,
        brightness: IntArray,
        threshold: Int,
        labels: IntArray,
        stack: IntArray,
    ): Region {
        val region = Region(label)
        var top = 0
        labels[start] = label
        stack[top++] = start

        val changedAt = ((frameCount - 1) / 4).coerceAtLeast(MIN_CHANGED_FRAMES)
        while (top > 0) {
            val cell = stack[--top]
            region.count++
            if (mChanges[cell] >= changedAt) region.changedCells++

            val cx = cell % cols
            val cy = cell / cols
            if (cx > 0) top = push(cell - 1, label, brightness, threshold, labels, stack, top)
            if (cx < cols - 1) top = push(cell + 1, label, brightness, threshold, labels, stack, top)
            if (cy > 0) top = push(cell - cols, label, brightness, threshold, labels, stack, top)
            if (cy < rows - 1) {
                top = push(cell + cols, label, brightness, threshold, labels, stack, top)
            }
        }
        return region
    }

    /** Помечает ячейку на входе, поэтому дважды на стек она не попадёт и он не переполнится. */
    private fun push(
        cell: Int,
        label: Int,
        brightness: IntArray,
        threshold: Int,
        labels: IntArray,
        stack: IntArray,
        top: Int,
    ): Int {
        if (labels[cell] != UNLABELED || brightness[cell] <= threshold) return top
        labels[cell] = label
        stack[top] = cell
        return top + 1
    }

    /** Чем закончилась попытка — по этому UI выбирает, что сказать пользователю. */
    enum class Code {
        FOUND,

        /** Кадры не пришли: камеру занял кто-то другой, привязка не состоялась. */
        NO_FRAMES,

        /** В кадре нечему светиться — телевизор выключен или смотрит не туда. */
        TOO_DARK,

        /** Что-то светится, но на экран не похоже: нет границ, формы, размера. */
        NO_SCREEN,
    }

    /** Итог попытки: [corners] равен null, когда ничего пригодного не нашлось. */
    class Detection private constructor(
        /** TL, TR, BR, BL в нормализованных 0..1. */
        val corners: FloatArray?,
        val code: Code,
        /** Почему детектор отказался — строкой в лог. У успеха пустая. */
        val reason: String,
    ) {
        companion object {
            fun found(corners: FloatArray) = Detection(corners, Code.FOUND, "")
            fun rejected(code: Code, reason: String) = Detection(null, code, reason)
        }
    }

    private class Region(val label: Int) {
        var count = 0
        var changedCells = 0

        /** Доля площади области, которая за окно замеров хоть сколько-нибудь менялась. */
        val changedFraction: Float
            get() = if (count == 0) 0f else changedCells.toFloat() / count
    }

    companion object {
        const val DEFAULT_COLS = 96
        const val DEFAULT_ROWS = 72

        /** Ниже этого самое яркое в кадре слишком тускло для включённого экрана. */
        private const val MIN_SCREEN_LUMA = 40

        /** Минимальный разрыв между средними яркого и тёмного классов. */
        private const val MIN_CONTRAST = 25

        /** Изменение яркости ячейки, начиная с которого это движение, а не шум матрицы. */
        private const val CELL_CHANGE_LEVEL = 8

        /** Сколько раз ячейка должна измениться, чтобы считаться живой. */
        private const val MIN_CHANGED_FRAMES = 2

        /** Доля меняющейся площади, начиная с которой область считается экраном с видео. */
        private const val VIDEO_COVERAGE = 0.25f

        private const val MIN_AREA_FRACTION = 0.04f

        /**
         * Наименьшее яркое ядро, от которого имеет смысл дорастать до панели. На порядок
         * меньше итогового предела: у фильма с тёмной картинкой ярким бывает лишь угол
         * кадра, а сама панель отличается от комнаты уже вторым, низким порогом.
         */
        private const val MIN_CORE_FRACTION = 0.004f

        // Удачно наведённая камера может отдать панель почти во весь кадр; равномерно
        // светлую комнату отсекает проверка контраста выше, а не этот предел.
        private const val MAX_AREA_FRACTION = 0.95f

        private const val MIN_SPAN_FRACTION = 0.15f
        private const val MIN_FILL_RATIO = 0.6f
        private const val MIN_ASPECT = 0.3f
        private const val MAX_ASPECT = 6.0f

        private const val MIN_EDGE_POINTS = 4
        private const val OUTLIER_FACTOR = 2f

        /** Насколько подогнанный угол может отойти от грубого, в долях кадра. */
        private const val MAX_FIT_DRIFT = 0.1f

        /** Минимальный разрыв между тусклой частью экрана и комнатой, чтобы решиться расти. */
        private const val MIN_GROWTH_CONTRAST = 10


        private const val UNLABELED = -1
        private const val NONE = -1

        /**
         * Сошлись ли два независимых замера. Окно делится пополам, и если половины дали
         * разные четырёхугольники, ответ считается случайным и не применяется — лучше
         * оставить прежние углы, чем выставить наугад.
         */
        fun agree(first: FloatArray?, second: FloatArray?, tolerance: Float): Boolean {
            if (first == null || second == null) return false
            for (i in 0 until 8) {
                if (abs(first[i] - second[i]) > tolerance) return false
            }
            return true
        }

        /** Метод Оцу: порог, максимизирующий межклассовую дисперсию. */
        internal fun otsuThreshold(values: IntArray): Int {
            val histogram = IntArray(256)
            for (value in values) histogram[value.coerceIn(0, 255)]++

            val total = values.size
            var totalSum = 0L
            for (level in 0..255) totalSum += level.toLong() * histogram[level]

            var backgroundSum = 0L
            var backgroundCount = 0
            var bestVariance = -1.0
            var threshold = 0

            for (level in 0..255) {
                backgroundCount += histogram[level]
                if (backgroundCount == 0) continue
                val foregroundCount = total - backgroundCount
                if (foregroundCount == 0) break

                backgroundSum += level.toLong() * histogram[level]
                val backgroundMean = backgroundSum.toDouble() / backgroundCount
                val foregroundMean = (totalSum - backgroundSum).toDouble() / foregroundCount
                val delta = backgroundMean - foregroundMean
                val variance = backgroundCount.toDouble() * foregroundCount * delta * delta

                if (variance > bestVariance) {
                    bestVariance = variance
                    threshold = level
                }
            }
            return threshold
        }

        /** Строки уходят в лог, поэтому формат не зависит от локали устройства. */
        private fun format2(value: Float): String = String.format(Locale.US, "%.2f", value)

        private fun quadArea(xs: IntArray, ys: IntArray): Float {
            var sum = 0
            for (i in 0 until 4) {
                val j = (i + 1) % 4
                sum += xs[i] * ys[j] - xs[j] * ys[i]
            }
            return abs(sum) / 2f
        }
    }
}
