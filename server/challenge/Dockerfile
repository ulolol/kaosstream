FROM mcr.microsoft.com/playwright:v1.58.2-noble
WORKDIR /app
COPY package.json .
RUN npm install --omit=dev
COPY server.js .
EXPOSE 3210
CMD ["xvfb-run", "--server-args=-screen 0 1280x1024x24 -ac", "node", "server.js"]
