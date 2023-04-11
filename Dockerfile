# Use an official Java runtime as a parent image
FROM openjdk:11-jdk-slim

# Set the working directory to /app
WORKDIR /app

# Copy the application files into the container
COPY src/Server.java /app
COPY src/Client.java /app

# Compile the application
RUN javac Server.java
RUN javac Client.java

# Expose port 8080 for the server
EXPOSE 8080

# Start the server when the container starts
CMD ["java", "Server"]
