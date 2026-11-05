# stacklab: the engine's alignment and stacking as a command-line tool for the x86_64 emulator,
# run over a real session's cached frames. Built and run by run.ps1; not part of the app.
cmake_minimum_required(VERSION 3.22.1)
project(stacklab CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(ENGINE ${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp)

add_executable(stacklab lab.cpp
        ${ENGINE}/engine.cpp ${ENGINE}/sraw.cpp ${ENGINE}/bayer.cpp ${ENGINE}/stars.cpp
        ${ENGINE}/align.cpp ${ENGINE}/stack.cpp ${ENGINE}/post.cpp ${ENGINE}/post2.cpp
        ${ENGINE}/preview.cpp ${ENGINE}/mask.cpp ${ENGINE}/trails.cpp ${ENGINE}/meteor.cpp
        ${ENGINE}/tiffout.cpp ${ENGINE}/studio.cpp ${ENGINE}/timelapse.cpp)
target_include_directories(stacklab PRIVATE ${ENGINE})
# Same flags as the app's engine, so the lab measures the code the phone runs.
target_compile_options(stacklab PRIVATE -O3 -fno-math-errno -Wall)
target_link_libraries(stacklab log)
