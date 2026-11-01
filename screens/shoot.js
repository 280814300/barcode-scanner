const { chromium } = require('playwright');
const path = require('path');

(async () => {
  const fileUrl = 'file://' + path.resolve(__dirname, 'screens.html');
  const browser = await chromium.launch();
  const page = await browser.newPage({ deviceScaleFactor: 3, viewport: { width: 460, height: 1000 } });
  await page.goto(fileUrl);
  await page.waitForTimeout(300);

  const shots = [
    { sel: '#screen-main',  out: 'shot-main.png' },
    { sel: '#screen-edit',  out: 'shot-edit.png' },
    { sel: '#screen-batch', out: 'shot-batch.png' },
    { sel: '#screen-plugins', out: 'shot-plugins.png' },
  ];
  for (const s of shots) {
    const el = await page.$(s.sel);
    await el.screenshot({ path: path.resolve(__dirname, s.out) });
    console.log('saved', s.out);
  }
  await browser.close();
})();
