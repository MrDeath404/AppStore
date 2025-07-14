if [ -n "$1" ]; then
  ./gradlew assemble"$1"
  exit
fi
./gradlew assemble
