const fs = require('fs');

function checkFile(path) {
    const code = fs.readFileSync(path, 'utf8');
    let depth = 0;
    let inString = false;
    let stringChar = '';
    let inComment = false;
    let inLineComment = false;
    let errors = [];

    for (let i = 0; i < code.length; i++) {
        const c = code[i];
        const next = code[i + 1];

        if (inLineComment) {
            if (c === '\n') inLineComment = false;
            continue;
        }
        if (inComment) {
            if (c === '*' && next === '/') {
                inComment = false;
                i++;
            }
            continue;
        }
        if (inString) {
            if (c === '\\') {
                i++; // escape
            } else if (c === stringChar) {
                inString = false;
            }
            continue;
        }

        if (c === '/' && next === '/') {
            inLineComment = true;
            i++;
            continue;
        }
        if (c === '/' && next === '*') {
            inComment = true;
            i++;
            continue;
        }
        if (c === '"' || c === "'") {
            if (code.substring(i, i + 3) === '"""') {
                inString = true;
                stringChar = '"""';
                // handle multiline string
                let end = code.indexOf('"""', i + 3);
                if (end !== -1) {
                    i = end + 2;
                    inString = false;
                }
                continue;
            }
            inString = true;
            stringChar = c;
            continue;
        }

        if (c === '{') depth++;
        if (c === '}') {
            depth--;
            if (depth < 0) {
                errors.push(`Extra closing brace at char ${i}`);
            }
        }
    }

    console.log(`${path} -> Final depth: ${depth}, Errors: ${errors.length}`);
}

checkFile('app/src/main/java/com/huangguo/tv/bridge/WebScraperBridge.kt');
checkFile('app/src/main/java/com/huangguo/tv/MainActivity.kt');
checkFile('app/src/main/java/com/huangguo/tv/PlayerActivity.kt');
checkFile('app/src/main/java/com/huangguo/tv/adapter/DramaAdapter.kt');
