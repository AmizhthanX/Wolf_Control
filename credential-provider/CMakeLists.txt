cmake_minimum_required(VERSION 3.21)
project(WolfCredentialProvider LANGUAGES CXX)

# A credential provider is loaded by LogonUI, which is a system process running
# before any user profile exists. Static runtime linking means there is no
# redistributable to install and nothing to go missing at the one moment nobody
# can log in to fix it.
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(WolfCredentialProvider SHARED
    src/dll.cpp
    src/ClassFactory.cpp
    src/Provider.cpp
    src/Credential.cpp
    src/PipeClient.cpp
    WolfCredentialProvider.def
)

target_include_directories(WolfCredentialProvider PRIVATE src)

target_compile_definitions(WolfCredentialProvider PRIVATE
    UNICODE _UNICODE
    WIN32_LEAN_AND_MEAN
    NOMINMAX
)

target_compile_options(WolfCredentialProvider PRIVATE /W4 /permissive-)

target_link_libraries(WolfCredentialProvider PRIVATE
    credui
    secur32
    shlwapi
    crypt32      # CryptStringToBinary, for the base64 on the pipe
    advapi32     # LookupAccountName / ConvertSidToStringSid
)

set_target_properties(WolfCredentialProvider PROPERTIES
    OUTPUT_NAME "WolfCredentialProvider"
    PREFIX ""
)
