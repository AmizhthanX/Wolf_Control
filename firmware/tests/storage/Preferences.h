#pragma once
#include "Arduino.h"
#include <map>
#include <vector>
class Preferences {
 std::string ns;
 public:
 inline static std::map<std::string, std::map<std::string, std::vector<uint8_t>>> data;
 inline static int writes=0;
 bool begin(const char* name,bool) {ns=name;return true;}
 bool isKey(const char* k) {return data[ns].count(k);}
 String getString(const char* k,const String& fallback="") {if(!isKey(k))return fallback;auto& v=data[ns][k];return String(v.begin(),v.end());}
 size_t putString(const char* k,const String& v) {data[ns][k]={v.begin(),v.end()};++writes;return v.size();}
 size_t getBytesLength(const char* k) {return isKey(k)?data[ns][k].size():0;}
 size_t getBytes(const char* k,void* out,size_t len) {if(!isKey(k)||getBytesLength(k)>len)return 0;auto& v=data[ns][k];memcpy(out,v.data(),v.size());return v.size();}
 size_t putBytes(const char* k,const void* v,size_t n) {auto p=(const uint8_t*)v;data[ns][k]={p,p+n};++writes;return n;}
 uint8_t getUChar(const char* k,uint8_t fallback) {return isKey(k)?data[ns][k][0]:fallback;}
 size_t putUChar(const char* k,uint8_t v) {data[ns][k]={v};++writes;return 1;}
 bool remove(const char* k) {return data[ns].erase(k);}
};
