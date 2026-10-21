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
        mask.cpp
        trails.cpp
        meteor.cpp
        tiffout.cpp
        selftest.cpp)

# NOT -ffast-math: it implies -ffinite-math-only, which lets Clang assume no NaN/Inf exists and
# folds away the engine's deliberate NaN guards (post.cpp, tiffout.cpp, meteor.cpp, mask.cpp) and
# std::isfinite in json_mini.h. -fno-math-errno keeps most of the speed without that hazard.
target_compile_options(starcam PRIVATE -O3 -fno-math-errno -Wall)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
find_library(android-lib android)

target_link_libraries(starcam ${log-lib} ${jnigraphics-lib} ${android-lib})
