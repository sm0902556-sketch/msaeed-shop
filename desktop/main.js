const { app, BrowserWindow, shell, Menu } = require('electron');
const path = require('path');
const SITE = 'https://sm0902556-sketch.github.io/msaeed-shop/';

if (!app.requestSingleInstanceLock()) { app.quit(); }
let win = null;

function offlinePage() {
  const html = '<!DOCTYPE html><html dir="rtl"><body style="font-family:Tahoma,sans-serif;text-align:center;padding:80px 20px;background:#fbf8f3;color:#1e3a8a">' +
    '<h2>مفيش اتصال بالإنترنت</h2><p>البرنامج محتاج نت عشان يفتح. اتأكد من الاتصال ودوس جرب تاني.</p>' +
    '<button style="font-size:18px;padding:10px 24px;border-radius:10px;border:0;background:#1d4ed8;color:#fff;cursor:pointer" onclick="location.href=\'' + SITE + '\'">جرب تاني</button>' +
    '<p style="margin-top:40px;color:#888">المبرمج محمد سعيد — 01123840671</p></body></html>';
  return 'data:text/html;charset=utf-8,' + encodeURIComponent(html);
}

function create() {
  win = new BrowserWindow({
    width: 1366, height: 860, minWidth: 900, minHeight: 600,
    title: 'محل الإخوة', icon: path.join(__dirname, 'icon.png'),
    autoHideMenuBar: true, backgroundColor: '#fbf8f3',
    webPreferences: { contextIsolation: true, sandbox: true }
  });
  Menu.setApplicationMenu(null);
  win.maximize();
  win.loadURL(SITE);
  win.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: 'deny' }; });
  win.webContents.on('will-navigate', (e, url) => {
    if (!url.startsWith(SITE) && !url.startsWith('data:')) { e.preventDefault(); shell.openExternal(url); }
  });
  win.webContents.on('did-fail-load', (e, code, desc, url, isMain) => {
    if (isMain && code !== -3) win.loadURL(offlinePage());
  });
  win.webContents.on('before-input-event', (e, input) => {
    if (input.type === 'keyDown' && (input.key === 'F5' || (input.control && input.key.toLowerCase() === 'r'))) {
      e.preventDefault(); win.webContents.reloadIgnoringCache();
    }
  });
}

app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });
app.whenReady().then(create);
app.on('window-all-closed', () => app.quit());
