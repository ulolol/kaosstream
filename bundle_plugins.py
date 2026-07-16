import os
import sys
import json
import zipfile
import urllib.request
import shutil
import subprocess

REPO_URLS = [
    "https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/CS.json",
    "https://raw.githubusercontent.com/Kraptor123/cs-Karma/refs/heads/master/repo.json",
    "https://raw.githubusercontent.com/Reflex755/ReflexRepo/refs/heads/builds/repo.json",
    "https://raw.githubusercontent.com/ycngmn/CuxPlug/refs/heads/main/repo.json",
    "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json",
    "https://raw.githubusercontent.com/CakesTwix/cloudstream-extensions-uk/master/repo.json",
    "https://raw.githubusercontent.com/rockhero1234/cinephile/refs/heads/builds/repo.json",
    "https://raw.githubusercontent.com/techtanic/SkillShare-Repo/refs/heads/builds/repo.json"
]

TEMP_DIR = "temp"
OUTPUT_DIR = "bundled-plugins"
DEX_TOOLS_ZIP = "dex-tools-v2.4.zip"
DEX_TOOLS_DIR = "dex-tools"

def setup_directories():
    os.makedirs(TEMP_DIR, exist_ok=True)
    os.makedirs(OUTPUT_DIR, exist_ok=True)

def download_file(url, target_path):
    print(f"Downloading: {url} -> {target_path}")
    req = urllib.request.Request(
        url, 
        headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'}
    )
    with urllib.request.urlopen(req) as response, open(target_path, 'wb') as out_file:
        shutil.copyfileobj(response, out_file)

def setup_d2j():
    if os.path.exists(DEX_TOOLS_DIR):
        return
    
    zip_path = os.path.join(TEMP_DIR, DEX_TOOLS_ZIP)
    if not os.path.exists(zip_path):
        url = "https://github.com/pxb1988/dex2jar/releases/download/v2.4/dex-tools-v2.4.zip"
        download_file(url, zip_path)
    
    print("Extracting dex-tools...")
    with zipfile.ZipFile(zip_path, 'r') as zip_ref:
        zip_ref.extractall(TEMP_DIR)
        
    extracted_folder = os.path.join(TEMP_DIR, "dex-tools-v2.4")
    if os.path.exists(extracted_folder):
        shutil.move(extracted_folder, DEX_TOOLS_DIR)
    
    # Make shell scripts executable on Linux
    if sys.platform != "win32":
        for root, dirs, files in os.walk(DEX_TOOLS_DIR):
            for file in files:
                if file.endswith(".sh"):
                    os.chmod(os.path.join(root, file), 0o755)

def get_plugin_urls():
    plugin_urls = []
    for repo_url in REPO_URLS:
        try:
            print(f"Fetching repo: {repo_url}")
            repo_temp = os.path.join(TEMP_DIR, "repo_temp.json")
            download_file(repo_url, repo_temp)
            
            with open(repo_temp, 'r') as f:
                repo_data = json.load(f)
                
            plugin_lists = repo_data.get("pluginLists", [])
            for list_url in plugin_lists:
                print(f"Fetching plugin list: {list_url}")
                list_temp = os.path.join(TEMP_DIR, "list_temp.json")
                download_file(list_url, list_temp)
                
                with open(list_temp, 'r') as lf:
                    plugins = json.load(lf)
                    for plugin in plugins:
                        if "url" in plugin:
                            plugin_urls.append((plugin["name"], plugin["url"]))
        except Exception as e:
            print(f"Failed to fetch plugins from {repo_url}: {e}")
    return plugin_urls

def convert_cs3_to_jar(name, url):
    safe_name = name.replace(" ", "_")
    cs3_path = os.path.join(TEMP_DIR, f"{safe_name}.cs3")
    extracted_dir = os.path.join(TEMP_DIR, f"{safe_name}_extracted")
    classes_jar = os.path.join(TEMP_DIR, f"{safe_name}_classes.jar")
    classes_extracted = os.path.join(TEMP_DIR, f"{safe_name}_classes_extracted")
    final_jar_path = os.path.join(OUTPUT_DIR, f"{safe_name}.jar")

    if os.path.exists(final_jar_path):
        print(f"Plugin {name} already converted.")
        return

    try:
        # 1. Download .cs3 file
        download_file(url, cs3_path)
        
        # 2. Extract .cs3
        os.makedirs(extracted_dir, exist_ok=True)
        with zipfile.ZipFile(cs3_path, 'r') as zip_ref:
            zip_ref.extractall(extracted_dir)
            
        dex_path = os.path.join(extracted_dir, "classes.dex")
        if not os.path.exists(dex_path):
            print(f"No classes.dex found in {name}")
            return
            
        # 3. Convert dex to jar
        print(f"Translating DEX bytecode for {name}...")
        d2j_script = os.path.join(DEX_TOOLS_DIR, "d2j-dex2jar.bat" if sys.platform == "win32" else "d2j-dex2jar.sh")
        cmd = [d2j_script, dex_path, "-o", classes_jar, "--force"]
        
        env = os.environ.copy()
        java_home = "/home/kaos/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2"
        if os.path.exists(java_home):
            java_bin = os.path.join(java_home, "bin")
            env["PATH"] = java_bin + os.pathsep + env.get("PATH", "")
            env["JAVA_HOME"] = java_home

        subprocess.run(cmd, check=True, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        
        # 4. Extract translated jar classes
        os.makedirs(classes_extracted, exist_ok=True)
        with zipfile.ZipFile(classes_jar, 'r') as zip_ref:
            zip_ref.extractall(classes_extracted)
            
        # 5. Pack converted class files, manifest.json, and metadata into final jar file
        with zipfile.ZipFile(final_jar_path, 'w') as target_zip:
            # Add all translated classes
            for root, dirs, files in os.walk(classes_extracted):
                for file in files:
                    file_path = os.path.join(root, file)
                    arcname = os.path.relpath(file_path, classes_extracted)
                    target_zip.write(file_path, arcname)
            
            # Add manifest.json and other files from the original plugin
            for file in ["manifest.json", "icon.png"]:
                src_file = os.path.join(extracted_dir, file)
                if os.path.exists(src_file):
                    target_zip.write(src_file, file)
                    
        print(f"Successfully converted plugin: {name} -> {final_jar_path}")
    except Exception as e:
        print(f"Failed to convert plugin {name}: {e}")
    finally:
        # Cleanup temp workspaces for this plugin
        for path in [cs3_path, extracted_dir, classes_jar, classes_extracted]:
            if os.path.exists(path):
                if os.path.isdir(path):
                    shutil.rmtree(path)
                else:
                    os.remove(path)

def main():
    setup_directories()
    setup_d2j()
    
    print("Collecting plugin list from repositories...")
    plugins = get_plugin_urls()
    print(f"Found {len(plugins)} plugins to convert.")
    
    for name, url in plugins:
        print(f"\nProcessing plugin: {name}")
        convert_cs3_to_jar(name, url)
        
    print("\nAll conversions finished!")

if __name__ == "__main__":
    main()
