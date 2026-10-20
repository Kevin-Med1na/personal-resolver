# Etapa 1: Compilacion (Build)
FROM maven:3.9.6-eclipse-temurin-17-alpine AS builder
WORKDIR /app

# Copiamos primero el pom para aprovechar la caché de capas de Docker
COPY pom.xml .
RUN mvn dependency:go-offline

# Copiamos el código fuente
COPY src ./src

# Empaquetamos la app
RUN mvn clean package -DskipTests

# Etapa 2: Ejecucion (Run)
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Copiamos el jar desde la etapa builder
COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]