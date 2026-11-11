# Stelli's engine built natively for Linux, inside the image of the Dockerfile beside this file:
# the engine as a library, stacklab (tools/stacklab/lab.cpp), postlab (tools/postlab) and the
# self-test with a main().
# Same flags as the app's engine, so what is measured here is the code the phone runs.
# Built by run.ps1 with the repo mounted at /src.
cmake_minimum_required(VERSION 3.22)
project(stellinative CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(SRC /src CACHE PATH "the repo")
set(ENGINE ${SRC}/app/src/main/cpp)

# Every engine source but the JNI bridge (the only file that needs Android).
file(GLOB ENGINE_SOURCES ${ENGINE}/*.cpp)
list(FILTER ENGINE_SOURCES EXCLUDE REGEX "jni_bridge\.cpp$")

find_package(Threads REQUIRED)
add_library(engine STATIC ${ENGINE_SOURCES})
target_include_directories(engine PUBLIC ${ENGINE})
target_compile_options(engine PRIVATE -O3 -fno-math-errno -Wall)
target_link_libraries(engine PUBLIC Threads::Threads)

add_executable(stacklab ${SRC}/tools/stacklab/lab.cpp)
target_compile_options(stacklab PRIVATE -O3 -fno-math-errno -Wall)
target_link_libraries(stacklab engine)

add_executable(selftest ${SRC}/tools/native/selftest_main.cpp)
target_compile_options(selftest PRIVATE -O3 -fno-math-errno -Wall)
target_link_libraries(selftest engine)

add_executable(postlab ${SRC}/tools/postlab/lab.cpp ${SRC}/tools/postlab/scene.cpp)
target_include_directories(postlab PRIVATE ${SRC}/tools/postlab)
target_compile_options(postlab PRIVATE -O3 -fno-math-errno -Wall)
target_link_libraries(postlab engine)
