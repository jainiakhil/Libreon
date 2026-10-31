set -e
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
cd ~/engine-x86_64-slim
grep -q pack-relative-relocs android/Bootstrap/Makefile.shared ||
  sed -i 's|\$(CXX) -Wl,--build-id=sha1|$(CXX) -Wl,-z,pack-relative-relocs -Wl,--build-id=sha1|' android/Bootstrap/Makefile.shared
grep -c pack-relative-relocs android/Bootstrap/Makefile.shared
find . -name liblo-native-code.so -not -path ./instdir/\* -print -delete
make gb_COMPILEROPTFLAGS=-Os 2>&1 | tail -5
