# Build Instructions

## Prerequisites

- Java 17 or higher (currently have Java 24 installed)
- Maven 3.6 or higher (not currently installed)

## Installing Maven

### macOS (Homebrew)
```bash
brew install maven
```

### Manual Installation
1. Download Maven from https://maven.apache.org/download.cgi
2. Extract to a directory
3. Add to PATH:
```bash
export PATH=/path/to/maven/bin:$PATH
```

## Building the Project

Once Maven is installed:

```bash
# Clean and build
mvn clean package

# Skip tests
mvn clean package -DskipTests

# Run tests
mvn test

# Run the application
mvn spring-boot:run
```

## Docker Build (No Maven Required)

If you don't want to install Maven locally, use Docker:

```bash
# Build and run with Docker Compose
docker-compose up --build

# Or build the Docker image directly
docker build -t seat-reservation .
```

## Current Status

✅ Java 24 installed
❌ Maven not installed
✅ Project structure complete
✅ All source files written
✅ Dockerfile and docker-compose.yml ready

## Next Steps

1. Install Maven (or use Docker)
2. Run `mvn clean package` to verify build
3. Run `mvn spring-boot:run` to start the service
4. Run burst test: `./burst.py http://localhost:8080`
5. Deploy to cloud platform
