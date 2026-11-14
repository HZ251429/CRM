const sharp = require('sharp');
const grad = `<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#ea580c"/><stop offset="1" stop-color="#e11d48"/></linearGradient></defs>`;
const mark = (size, s) => `<text x="${size/2}" y="${size/2 + s*0.36}" text-anchor="middle" font-family="Segoe UI, Arial, sans-serif" font-weight="800" font-size="${s}" fill="#fff" letter-spacing="${-s*0.02}">HZ</text>`;
const icon = `<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024">${grad}<rect width="1024" height="1024" fill="url(#g)"/>${mark(1024, 400)}<text x="512" y="790" text-anchor="middle" font-family="Segoe UI, Arial, sans-serif" font-weight="700" font-size="96" letter-spacing="24" fill="#ffe4d6">CRM</text></svg>`;
const fg = `<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024">${mark(1024, 300)}<text x="512" y="700" text-anchor="middle" font-family="Segoe UI, Arial, sans-serif" font-weight="700" font-size="72" letter-spacing="18" fill="#ffe4d6">CRM</text></svg>`;
const bg = `<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024">${grad}<rect width="1024" height="1024" fill="url(#g)"/></svg>`;
const splash = (dark) => `<svg xmlns="http://www.w3.org/2000/svg" width="2732" height="2732">${grad}<rect width="2732" height="2732" fill="${dark ? '#140c18' : '#1c1027'}"/><rect x="1106" y="1046" width="520" height="520" rx="120" fill="url(#g)"/>${mark(2732, 220).replace('y="' + (2732/2 + 220*0.36) + '"', 'y="' + (1306 + 220*0.36) + '"')}<text x="1366" y="1720" text-anchor="middle" font-family="Segoe UI, Arial, sans-serif" font-weight="700" font-size="96" fill="#f5e9f0">HZ CRM</text></svg>`;
(async () => {
  await sharp(Buffer.from(icon)).png().toFile('assets/icon-only.png');
  await sharp(Buffer.from(fg)).png().toFile('assets/icon-foreground.png');
  await sharp(Buffer.from(bg)).png().toFile('assets/icon-background.png');
  await sharp(Buffer.from(splash(false))).png().toFile('assets/splash.png');
  await sharp(Buffer.from(splash(true))).png().toFile('assets/splash-dark.png');
  console.log('ok');
})();
