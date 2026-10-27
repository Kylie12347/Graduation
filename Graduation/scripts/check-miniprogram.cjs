/* 无外部依赖的静态检查：JS语法、JSON解析、页面四件套及WXML事件处理函数存在性。 */
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '..');
let count = 0;
function walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(p);
    else if (p.endsWith('.js')) { new vm.Script(fs.readFileSync(p, 'utf8'), { filename: p }); count++; }
    else if (p.endsWith('.json')) { JSON.parse(fs.readFileSync(p, 'utf8')); count++; }
  }
}
walk(path.join(root, 'miniprogram'));
walk(path.join(root, 'backend/src/main/resources/static'));
const app = JSON.parse(fs.readFileSync(path.join(root, 'miniprogram/app.json'), 'utf8'));
for (const page of app.pages) {
  for (const extension of ['.js', '.json', '.wxml', '.wxss']) {
    if (!fs.existsSync(path.join(root, 'miniprogram', page + extension))) throw new Error('缺少页面文件：' + page + extension);
  }
  const source = fs.readFileSync(path.join(root, 'miniprogram', page + '.js'), 'utf8');
  const template = fs.readFileSync(path.join(root, 'miniprogram', page + '.wxml'), 'utf8');
  for (const match of template.matchAll(/(?:bind|catch)(?:tap|input|change)="([\w]+)"/g)) {
    if (!new RegExp('\\b' + match[1] + '\\s*\\(').test(source)) throw new Error(page + '未定义事件：' + match[1]);
  }
}
console.log(`PASS：${count}个JS/JSON文件及${app.pages.length}个页面结构检查通过。`);
