URL ?= http://localhost:8080
.PHONY: build test run burst
build:
	mvn -q clean package -DskipTests
test:
	mvn -q test
run:
	java -jar target/seat-reservation-1.0.0.jar
burst:
	python3 burst.py $(URL)
