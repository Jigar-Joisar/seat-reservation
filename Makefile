URL ?= http://localhost:8080
.PHONY: build test run burst
build:
	mvn -q clean package -DskipTests
test:
	mvn -q test
run:
	./run.sh
burst:
	python3 burst.py $(URL)
