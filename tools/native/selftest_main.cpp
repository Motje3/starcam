// The engine's self-test (app/src/main/cpp/selftest.cpp) with a main(): prints its JSON report,
// exit code 0 when every check passes. Built by tools/native/CMakeLists.txt, run by run.ps1.
#include <cstdio>
#include <string>

#include "selftest.h"

int main(int argc, char** argv) {
    const std::string tmp = argc > 1 ? argv[1] : "/tmp/selftest";
    const std::string report = starcam::run_selftest(tmp);
    std::printf("%s\n", report.c_str());
    return report.rfind("{\"pass\":true", 0) == 0 ? 0 : 1;
}
