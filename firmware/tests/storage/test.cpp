#include <cassert>
#include <iostream>
#include "Preferences.h"
#include "../../src/store.cpp"
namespace wolfcrypto {
bool fromHex(const String& hex,uint8_t* out,size_t n) {if(hex.size()!=n*2)return false; for(size_t i=0;i<n;i++){try {out[i]=(uint8_t)std::stoi(hex.substr(i*2,2),nullptr,16);}catch(...){return false;}}return true;}
}
int main() {
 store::begin(); assert(store::deviceId()=="build-device");
 store::setDeviceId("current-device");store::setWifi("new-wifi", "");
 store::setCloud("https://new.example", "new-anon");
 store::setDeviceSecret(String(64,'b'));
 int writes=Preferences::writes;
 store::begin();assert(Preferences::writes==writes);
 assert(store::wifiPassword().empty());
 auto& primary=Preferences::data["wolfpc"];
 primary.erase("devid");primary.erase("url");primary.erase("anon");primary["secret"]={1,2};
 store::begin();assert(store::deviceId()=="current-device");
 assert(store::supabaseUrl()=="https://new.example");assert(store::supabaseAnonKey()=="new-anon");
 uint8_t secret[32]{};assert(store::deviceSecret(secret));assert(secret[0]==0xbb);
 primary.clear();store::begin();assert(store::deviceId()=="current-device");
 assert(store::wifiSsid()=="new-wifi");assert(store::wifiPassword().empty());
 assert(store::deviceSecret(secret)&&secret[0]==0xbb);
 assert(store::trustedKeyCount()==0);
 writes=Preferences::writes;store::begin();assert(Preferences::writes==writes);
 std::cout<<"PASS: initial provisioning, partial loss, malformed secret, primary loss, rotated secret, empty password, no repeated writes, no key resurrection\n";
}
