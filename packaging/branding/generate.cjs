// Run with Node.js and Sharp available: node packaging/branding/generate.cjs
const fs = require('node:fs');
const path = require('node:path');
const sharp = require('sharp');

const root = path.resolve(__dirname, '../..');
const source = path.join(root, 'design/mikan.webp');
const densities = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 };
const background = '#FFF4E8';
const iconBackground = '#081D38';
const write = (relative, bytes) => {
    const target = path.join(root, relative);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, bytes);
};

async function solidMark(color) {
    const { data, info } = await sharp(source).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
    for (let offset = 0; offset < data.length; offset += 4) {
        data[offset] = color[0];
        data[offset + 1] = color[1];
        data[offset + 2] = color[2];
    }
    return sharp(data, { raw: info }).png().toBuffer();
}

async function foreground(mark, size, extent = 66) {
    const width = Math.round(size * extent / 108);
    const image = await sharp(mark).resize(width, width).png().toBuffer();
    const inset = Math.round((size - width) / 2);
    return sharp({ create: { width: size, height: size, channels: 4, background: '#00000000' } })
        .composite([{ input: image, left: inset, top: inset }]).png().toBuffer();
}

async function generate() {
    const original = fs.readFileSync(source);
    const white = await solidMark([255, 255, 255]);
    const gray = await solidMark([154, 154, 154]);
    write('design/src/main/res/drawable-nodpi/ic_clash.webp', original);
    write('design/mikan.png', await sharp(original).png().toBuffer());
    write('app/src/main/res/drawable-nodpi/ic_launcher_foreground.png', await foreground(original, 432));
    write('app/src/main/res/drawable-nodpi/ic_launcher_monochrome.png', await foreground(white, 432));
    write('service/src/main/res/drawable-nodpi/widget_mikan.webp', original);
    write('service/src/main/res/drawable-nodpi/widget_mikan_off.png', gray);

    for (const [density, scale] of Object.entries(densities)) {
        const size = Math.round(48 * scale);
        // Legacy icons use their entire canvas; adaptive icons have a separate mask.
        const mark = await foreground(original, size, 86.4);
        const square = await sharp({ create: { width: size, height: size, channels: 4, background: iconBackground } })
            .composite([{ input: mark }]).webp({ lossless: true }).toBuffer();
        const circle = Buffer.from(`<svg width="${size}" height="${size}"><circle cx="${size / 2}" cy="${size / 2}" r="${size / 2}" fill="${iconBackground}"/></svg>`);
        const round = await sharp(circle).composite([{ input: mark }]).webp({ lossless: true }).toBuffer();
        write(`app/src/main/res/mipmap-${density}/ic_launcher.webp`, square);
        write(`app/src/main/res/mipmap-${density}/ic_launcher_round.webp`, round);
        write(`service/src/main/res/drawable-${density}/ic_logo_service.png`,
            await sharp(white).resize(Math.round(24 * scale)).png().toBuffer());
    }

    const bannerMark = await sharp(original).resize(80, 80).png().toBuffer();
    const bannerText = Buffer.from('<svg width="320" height="180"><text x="134" y="107" font-family="sans-serif" font-size="40" font-weight="bold" fill="#493322">Mikan</text></svg>');
    write('app/src/main/res/mipmap-xhdpi/ic_banner.png',
        await sharp({ create: { width: 320, height: 180, channels: 4, background } })
            .composite([{ input: bannerMark, left: 36, top: 46 }, { input: bannerText }]).png().toBuffer());
    console.log('Generated Android brand assets from the transparent panel logo.');
}

generate().catch(error => { console.error(error); process.exitCode = 1; });
