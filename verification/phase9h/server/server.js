const http = require("http");
const fs = require("fs");
const path = require("path");
const server = http.createServer((req, res) => {
  if (req.url === "/" || req.url === "/index.html") {
    res.writeHead(200, {"Content-Type": "text/html"});
    fs.createReadStream("index.html").pipe(res);
  } else if (req.url === "/manga_test_e11.jpg") {
    res.writeHead(200, {"Content-Type": "image/jpeg"});
    fs.createReadStream("manga_test_e11.jpg").pipe(res);
  } else {
    res.writeHead(404);
    res.end("Not found");
  }
});
server.listen(8888, () => console.log("Server running on port 8888"));
