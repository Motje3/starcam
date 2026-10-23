cmake_minimum_required(VERSION 3.22.1)
project(starcam CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(starcam SHARED
        jni_bridge.cpp
        engine.cpp
        sraw.cpp
        bayer.cpp
        stars.cpp
        align.cpp
        stack.cpp
        post.cpp
        post2.cpp
        preview.cpp
        mask.cpp
        trails.cpp
        meteor.cpp
        tiffout.cpp
        selftest.cpp
        studio.cpp)

# NOT -ffast-math: it implies -ffinite-math-only, which lets Clang assume no NaN/Inf exists and
# folds away the engine's deliberate NaN guards (post.cpp, tiffout.cpp, meteor.cpp, mask.cpp) and
# std::isfinite in json_mini.h. -fno-math-errno keeps most of the speed without that hazard.
target_compile_options(starcam PRIVATE -O3 -fno-math-errno -Wall)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
find_library(android-lib android)

target_link_libraries(starcam ${log-lib} ${jnigraphics-lib} ${android-lib})

# 16 KB page alignment for the LOAD segments: devices with 16 KB pages (and Google Play, for apps
# targeting Android 15+) need it. NDK r27 still defaults to 4 KB; r28+ makes this the default.
target_link_options(starcam PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
