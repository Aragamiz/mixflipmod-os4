param(
    [switch]$Install
)
$ErrorActionPreference = "Stop"
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$SrcDir = Join-Path $Here "src"
$Tools = if ($env:FLIPCLOCK_TOOLS) {
    (Resolve-Path $env:FLIPCLOCK_TOOLS).Path
} else {
    (Resolve-Path (Join-Path $Here "..\..\04_工具链\tools")).Path
}
$JdkBin = if (Test-Path "$Tools\bin\javac.exe") { "$Tools\bin" } else { "$Tools\jdk-17.0.2\bin" }
$JadxJar = (Resolve-Path "$Tools\jadx\lib\jadx-1.5.6-all.jar").Path
$BaseMod = if ($env:MIXFLIPMOD_BASE_APK) {
    (Resolve-Path $env:MIXFLIPMOD_BASE_APK).Path
} else {
    (Resolve-Path (Join-Path $Here "..\..\07_LSPosed环境备份\模块APK\cn.zeldati.mixflipmod.apk")).Path
}

# 读取本地签名配置（优先环境变量，其次 keystore.properties，不向仓库硬编码密码）
$KsFile = $env:FLIPCLOCK_KS
$KsPass = $env:FLIPCLOCK_KS_PASS
$KeyAlias = $env:FLIPCLOCK_KEY_ALIAS
$KeyPass = $env:FLIPCLOCK_KEY_PASS
$PropsPath = Join-Path $Here "keystore.properties"
if (Test-Path $PropsPath) {
    Get-Content $PropsPath | ForEach-Object {
        if ($_ -match '^\s*([^#=]+?)\s*=\s*(.+?)\s*$') {
            $k = $Matches[1]; $v = $Matches[2]
            if ($k -eq "storeFile" -and !$KsFile) { $KsFile = $v }
            elseif ($k -eq "storePassword" -and !$KsPass) { $KsPass = $v }
            elseif ($k -eq "keyAlias" -and !$KeyAlias) { $KeyAlias = $v }
            elseif ($k -eq "keyPassword" -and !$KeyPass) { $KeyPass = $v }
        }
    }
}
if (!$KsFile) { throw "Missing keystore config: create keystore.properties (see keystore.properties.example) or set FLIPCLOCK_KS env var." }
if (![System.IO.Path]::IsPathRooted($KsFile)) { $KsFile = (Resolve-Path (Join-Path $Here $KsFile)).Path }
if (!$KeyAlias) { $KeyAlias = "flipclock" }

$TargetPkg = "io.github.aragamiz.mixflipmod_os4"
$TargetVerCode = "9"
$TargetVer = "1.0.12-flip2(re)"
$ReleaseTag = "$TargetVerCode-$TargetVer"
$ReleaseDir = Join-Path $Here ("release\" + $ReleaseTag)
New-Item -ItemType Directory -Path $ReleaseDir -Force | Out-Null
$ReleaseApk = Join-Path $ReleaseDir ("${TargetPkg}_v${TargetVer}.apk")
$WorkDir = Join-Path $env:TEMP "flipextra_pub_stage"

if (Test-Path $WorkDir) { Remove-Item -Recurse -Force $WorkDir }
New-Item -ItemType Directory -Path "$WorkDir\stub_classes","$WorkDir\classes","$WorkDir\dex","$WorkDir\patcher" -Force | Out-Null

try {
    Copy-Item -Recurse "$SrcDir\libxposed_stub" "$WorkDir\libxposed_stub" -Force
    Copy-Item -Recurse "$SrcDir\app" "$WorkDir\app" -Force
    Copy-Item (Join-Path $Here "patcher\DexPatcher.java") "$WorkDir\DexPatcher.java" -Force
    Copy-Item "$Tools\android-all.jar" "$WorkDir\android-all.jar" -Force
    Copy-Item $KsFile "$WorkDir\release.keystore" -Force
    Copy-Item $BaseMod "$WorkDir\base_mod.apk" -Force

    Push-Location $WorkDir

    # 1. 编译 libxposed stub & 扩展模块源码 -> classes2.dex
    $StubFiles = (Get-ChildItem -Recurse -Filter *.java "libxposed_stub").FullName
    & "$JdkBin\javac.exe" -encoding UTF-8 -source 11 -target 11 -nowarn `
        -cp "android-all.jar" -d "stub_classes" $StubFiles
    if ($LASTEXITCODE -ne 0) { throw "javac stub failed" }

    $JavaFiles = (Get-ChildItem -Recurse -Filter *.java "app/src/main/java").FullName
    & "$JdkBin\javac.exe" -encoding UTF-8 -source 11 -target 11 -nowarn `
        -cp "android-all.jar;stub_classes" -d "classes" $JavaFiles
    if ($LASTEXITCODE -ne 0) { throw "javac classes failed" }

    $ClassFiles = (Get-ChildItem -Recurse -Filter *.class "classes").FullName
    & "$JdkBin\java.exe" -cp "$Tools\build-tools\lib\d8.jar" com.android.tools.r8.D8 `
        --lib "android-all.jar" --min-api 29 --output "dex" $ClassFiles
    if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

    # 2. 编译并运行 DexPatcher，向原包 classes.dex 注入 ExtraPrefsUI 入口回调
    & "$JdkBin\javac.exe" -encoding UTF-8 -source 11 -target 11 -nowarn `
        -cp "$JadxJar" -d "patcher" "DexPatcher.java"
    if ($LASTEXITCODE -ne 0) { throw "javac DexPatcher failed" }

    python -c "
import zipfile
with zipfile.ZipFile('base_mod.apk', 'r') as z:
    z.extract('classes.dex', '.')
"
    & "$JdkBin\java.exe" -cp "patcher;$JadxJar" DexPatcher "classes.dex" "patched_classes.dex"
    if ($LASTEXITCODE -ne 0) { throw "DexPatcher failed" }

    # 3. 合并打包（含 AndroidManifest 包名/版本号重写 + trio.mtz 文本居中微调 + java_init.list 注册）
    @'
import zipfile, struct, sys

target_pkg = sys.argv[1]
target_ver = sys.argv[2]

def patch_trio_mtz(mtz_bytes):
    import io
    in_bio = io.BytesIO(mtz_bytes)
    out_bio = io.BytesIO()
    with zipfile.ZipFile(in_bio, 'r') as zin, zipfile.ZipFile(out_bio, 'w', zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            if item.filename == 'manifest.xml':
                txt = data.decode('utf-8')
                txt = txt.replace('width="420" height="260" align="left" alignV="top"', 'width="420" height="260" align="center" alignV="center"')
                txt = txt.replace('y="80" w="350"', 'y="40" w="380"')
                txt = txt.replace('y="136" w="350"', 'y="104" w="380"')
                txt = txt.replace('y="192" w="350"', 'y="168" w="380"')
                txt = txt.replace('align="left"', 'align="center"')
                data = txt.encode('utf-8')
            zout.writestr(item, data)
    return out_bio.getvalue()

def patch_axml_strings(data, replacements):
    buf = bytearray(data)
    sp_off = 8
    sp_type, sp_hdr_sz, sp_chunk_sz, str_cnt, sty_cnt, flags, str_start, sty_start = struct.unpack_from('<HHIIIIII', buf, sp_off)
    if sp_type != 0x0001:
        return data
    offsets_off = sp_off + sp_hdr_sz
    offsets = list(struct.unpack_from('<' + 'I' * str_cnt, buf, offsets_off))
    data_abs = sp_off + str_start
    end_abs = (sp_off + sty_start) if sty_start > 0 else (sp_off + sp_chunk_sz)
    new_data = bytearray()
    new_offsets = []
    for i in range(str_cnt):
        p = data_abs + offsets[i]
        char_len = struct.unpack_from('<H', buf, p)[0]
        raw = bytes(buf[p + 2 : p + 2 + char_len * 2])
        s = raw.decode('utf-16le')
        if s in replacements:
            s = replacements[s]
        enc = s.encode('utf-16le')
        new_offsets.append(len(new_data))
        new_data += struct.pack('<H', len(s)) + enc + b'\x00\x00'
    while len(new_data) % 4 != 0:
        new_data += b'\x00'
    tail_styles = bytes(buf[end_abs : sp_off + sp_chunk_sz])
    rest_axml = bytes(buf[sp_off + sp_chunk_sz :])
    new_sp_hdr = struct.pack('<HHIIIIII', sp_type, sp_hdr_sz,
        str_start + len(new_data) + len(tail_styles),
        str_cnt, sty_cnt, flags, str_start,
        (str_start + len(new_data)) if sty_start > 0 else 0)
    new_off_bytes = struct.pack('<' + 'I' * str_cnt, *new_offsets)
    out = bytearray(buf[:sp_off]) + new_sp_hdr + new_off_bytes + new_data + tail_styles + rest_axml
    struct.pack_into('<I', out, 4, len(out))
    return bytes(out)

repls = {
    'cn.zeldati.mixflipmod': target_pkg,
    '1.0.8-flip2': target_ver
}
with zipfile.ZipFile('base_mod.apk', 'r') as zin, zipfile.ZipFile('unsigned.apk', 'w') as zout:
    for item in zin.infolist():
        name = item.filename
        if name.startswith('META-INF/') and (name.endswith('.SF') or name.endswith('.RSA') or name.endswith('.MF')):
            continue
        if name in ('classes.dex', 'classes2.dex', 'META-INF/xposed/java_init.list'):
            continue
        content = zin.read(name)
        if name == 'AndroidManifest.xml':
            content = patch_axml_strings(content, repls)
        elif name == 'assets/widgets/mixflipmod_flip2_trio.mtz':
            content = patch_trio_mtz(content)
        zout.writestr(item, content)
    zout.write('patched_classes.dex', 'classes.dex', compress_type=zipfile.ZIP_DEFLATED)
    zout.write('dex/classes.dex', 'classes2.dex', compress_type=zipfile.ZIP_DEFLATED)
    zout.writestr('META-INF/xposed/java_init.list', 'll0\ncom.collasong.flipextra.MixFlipExtraModule\n', compress_type=zipfile.ZIP_DEFLATED)
'@ | python - "$TargetPkg" "$TargetVer"
    if ($LASTEXITCODE -ne 0) { throw "zip merge failed" }

    & "$Tools\build-tools\zipalign.exe" -p -f 4 "unsigned.apk" "aligned.apk"
    if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

    & "$JdkBin\java.exe" -jar "$Tools\build-tools\lib\apksigner.jar" sign `
        --ks "release.keystore" --ks-pass "pass:$KsPass" --key-pass "pass:$KeyPass" `
        --ks-key-alias $KeyAlias --out "signed.apk" "aligned.apk"
    if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

    Copy-Item "signed.apk" $ReleaseApk -Force
} finally {
    Pop-Location -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $WorkDir -ErrorAction SilentlyContinue
}

Write-Host "BUILD_MERGED_OK $((Get-Item $ReleaseApk).Length) bytes -> $ReleaseApk (Tag: $ReleaseTag)"

if ($Install) {
    & "$Tools\adb\adb.exe" install -r --no-incremental $ReleaseApk
}
