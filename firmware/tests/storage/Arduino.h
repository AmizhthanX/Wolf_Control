#pragma once
#include <string>
#include <cstring>
#include <cstdint>
using String = std::string;
struct SerialMock { template<class... T> void printf(const char*, T...) {} void println(const char*) {} };
inline SerialMock Serial;
