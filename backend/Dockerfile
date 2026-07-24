# --- Étape build : compile le jar (tests exécutés en CI, pas ici) ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline          # cache des dépendances (rebuilds plus rapides)
COPY src ./src
RUN mvn -B -ntp clean package -DskipTests

# --- Étape run : image légère avec juste le JRE ---
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
