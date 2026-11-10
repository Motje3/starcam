# A small Linux toolchain that builds and runs Stelli's native engine on the PC: the engine has
# no Android code outside jni_bridge.cpp, so stacklab and the self-test run here in seconds, with
# no emulator and no adb. Built and used by run.ps1.
FROM alpine:3.20
RUN apk add --no-cache g++ cmake ninja-build make libc-dev linux-headers \
    && ln -sf /usr/lib/ninja-build/bin/ninja /usr/bin/ninja
WORKDIR /work
