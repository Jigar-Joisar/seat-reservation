URL ?= http://localhost:8080
.PHONY: build test run burst burst-quick lint-api
build:
	mvn -q clean package -DskipTests
test:
	mvn -q test
run:
	./run.sh
burst:
	python3 burst.py $(URL)
burst-quick:
	python3 burst.py $(URL) --scale 0.2
lint-api:
	npx --yes @redocly/cli@latest lint src/main/resources/static/openapi.yaml
