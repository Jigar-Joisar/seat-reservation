URL ?= http://localhost:8080
.PHONY: build test run burst lint-api
build:
	mvn -q clean package -DskipTests
test:
	mvn -q test
run:
	./run.sh
burst:
	python3 burst.py $(URL)
lint-api:
	npx --yes @redocly/cli@latest lint src/main/resources/static/openapi.yaml
