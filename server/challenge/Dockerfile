FROM mcr.microsoft.com/playwright:v1.58.2-noble
WORKDIR /app
COPY package.json .
RUN npm install --omit=dev
COPY server.js .
EXPOSE 3210
CMD Xvfb :99 -screen 0 1280x1024x24 -ac +extension GLX +render -noreset & sleep 3 && DISPLAY=:99 node server.js
