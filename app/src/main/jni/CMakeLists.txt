cmake_minimum_required(VERSION 3.18)
project(ual_capture_servers C)

# Standalone root binary for MediaTek THAL screen capture.
# Uses libthal_capture.so (HIDL client) → do_capture_window() with DMA heap.
# Built as executable but named lib*.so so Android Gradle packages it in the APK.
add_executable(libmtk_thal_capture_server.so
    mtk_thal_capture_server.c
)

target_link_libraries(libmtk_thal_capture_server.so
    log
    dl
)

set_target_properties(libmtk_thal_capture_server.so PROPERTIES
    PREFIX ""
    SUFFIX ""
)

# Standalone root binary for Amlogic display write-back capture.
# Grabs post-blend frames (video + OSD) from amlvideo2 over V4L2 and streams RGB.
add_executable(libaml_wbcap_server.so
    aml_wbcap_server.c
)

target_link_libraries(libaml_wbcap_server.so
    log
)

set_target_properties(libaml_wbcap_server.so PROPERTIES
    PREFIX ""
    SUFFIX ""
)
