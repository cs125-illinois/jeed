FROM openjdk:14-jdk-slim
WORKDIR /
COPY *.jar containerrunner.jar
ENV CLASSPATH=/containerrunner.jar:/jeed/
ENTRYPOINT [ "java", "-ea", "--enable-preview", "-Xss256k", "edu.illinois.cs.cs125.jeed.containerrunner.MainKt" ]
