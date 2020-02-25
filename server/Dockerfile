FROM openjdk:13-jdk-slim
WORKDIR /
COPY *.jar jeed.jar
CMD [ "java", "--enable-preview", "-Xss256k", "-jar", "jeed.jar" ]
