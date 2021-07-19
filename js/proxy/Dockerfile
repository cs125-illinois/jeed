FROM node:14.17.3-alpine

ARG VERSION
ENV VERSION=$VERSION
ARG GIT_COMMIT
ENV GIT_COMMIT=$GIT_COMMIT
ARG BUILD_DATE
ENV BUILD_DATE=$BUILD_DATE

COPY dist/index.js .
COPY dist/index.js.map .

CMD [ "node", "--enable-source-maps", "index.js"]
