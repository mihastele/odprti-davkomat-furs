const fs = require('fs')
const path = require('path')

const dir = path.resolve(__dirname, '..', 'dist-legacy')
const html = `<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>OpenDavki</title>
    <link rel="icon" href="data:," />
    <link rel="stylesheet" href="app.css" />
  </head>
  <body>
    <div id="app"></div>
    <script src="app.js"></script>
  </body>
</html>
`
fs.mkdirSync(dir, { recursive: true })
fs.writeFileSync(path.join(dir, 'index.html'), html)
console.log('legacy web build: ' + path.join(dir, 'index.html'))
