const fs = require('fs');
const path = require('path');

function extractColors() {
    const colorsPath = path.resolve(__dirname, '../../app/src/main/res/values/colors.xml');
    if (!fs.existsSync(colorsPath)) {
        console.warn('colors.xml not found at', colorsPath);
        return;
    }
    const content = fs.readFileSync(colorsPath, 'utf8');
    const colorRegex = /<color name="([^"]+)">([^<]+)<\/color>/g;
    let match;
    let css = ':root {\n';
    
    // Add default design system variables
    css += `  --font-family: 'Inter', system-ui, -apple-system, sans-serif;\n`;
    css += `  --border-radius-sm: 4px;\n`;
    css += `  --border-radius-md: 8px;\n`;
    css += `  --border-radius-lg: 16px;\n`;
    css += `  --transition-speed: 0.2s;\n`;
    
    while ((match = colorRegex.exec(content)) !== null) {
        const name = match[1];
        const value = match[2].trim();
        
        // Resolve references like @color/colorPrimary
        if (value.startsWith('#')) {
            css += `  --color-${name}: ${value};\n`;
        } else if (value.startsWith('@color/')) {
            const ref = value.substring(7);
            css += `  --color-${name}: var(--color-${ref});\n`;
        }
    }
    css += '}\n';
    
    const cssDir = path.resolve(__dirname, 'css');
    fs.mkdirSync(cssDir, { recursive: true });
    fs.writeFileSync(path.join(cssDir, 'colors.css'), css);
    console.log('Successfully generated colors.css design system tokens.');
}

function extractStrings() {
    const resDir = path.resolve(__dirname, '../../app/src/main/res');
    const localesDir = path.resolve(__dirname, 'locales');
    fs.mkdirSync(localesDir, { recursive: true });
    
    if (!fs.existsSync(resDir)) {
        console.warn('res directory not found at', resDir);
        return;
    }
    
    const stringRegex = /<string name="([^"]+)"(?:[^>]*)>([^<]+)<\/string>/g;
    
    // Read root strings (en)
    const enPath = path.join(resDir, 'values/strings.xml');
    if (fs.existsSync(enPath)) {
        const content = fs.readFileSync(enPath, 'utf8');
        const translations = {};
        let match;
        // Reset regex state
        stringRegex.lastIndex = 0;
        while ((match = stringRegex.exec(content)) !== null) {
            const key = match[1];
            let val = match[2].trim();
            // Convert placeholders like %1$s or %d to simple {0}, {1}
            val = val.replace(/%(\d+)\$[sd]/g, '{$1}').replace(/%[sd]/g, '{0}');
            translations[key] = val;
        }
        fs.writeFileSync(path.join(localesDir, 'en.json'), JSON.stringify(translations, null, 2));
        console.log('Successfully generated en.json string translations.');
    }
}

extractColors();
extractStrings();
