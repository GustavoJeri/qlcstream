use std::{path::{Component, Path, PathBuf}, process::Command};

#[cfg(target_os = "macos")]
use tauri::Manager;

fn docker_output(arguments: &[&str]) -> Result<String, String> {
  let output = Command::new("docker")
    .args(arguments)
    .output()
    .map_err(|_| "Docker não está disponível. Inicie o Docker Desktop e tente novamente.".to_string())?;
  if !output.status.success() {
    return Err("Não foi possível localizar o ambiente Docker do QLC Stream.".to_string());
  }
  Ok(String::from_utf8_lossy(&output.stdout).trim().to_string())
}

fn library_file(relative_path: &str) -> Result<PathBuf, String> {
  let root = library_root()?;
  let relative = Path::new(relative_path);
  if relative.is_absolute() || relative.components().any(|part| matches!(part, Component::ParentDir | Component::RootDir | Component::Prefix(_))) {
    return Err("O caminho solicitado é inválido.".to_string());
  }
  let file = root.join(relative).canonicalize()
    .map_err(|_| "O arquivo não está mais disponível no computador.".to_string())?;
  if !file.starts_with(&root) || !file.is_file() {
    return Err("O caminho solicitado é inválido.".to_string());
  }
  Ok(file)
}

fn library_root() -> Result<PathBuf, String> {
  let container = docker_output(&[
    "ps", "--filter", "label=com.docker.compose.project=qlc-stream",
    "--filter", "label=com.docker.compose.service=backend", "--format", "{{.ID}}",
  ])?;
  let container = container.lines().next().filter(|id| !id.is_empty())
    .ok_or_else(|| "O container do backend do QLC Stream não está em execução.".to_string())?;
  let host_root = docker_output(&[
    "inspect", "--format", "{{range .Mounts}}{{if eq .Destination \"/data\"}}{{.Source}}{{end}}{{end}}", container,
  ])?;
  if host_root.is_empty() {
    return Err("A pasta de mídia do QLC Stream não foi encontrada.".to_string());
  }
  let root = Path::new(&host_root).canonicalize()
    .map_err(|_| "A pasta de mídia configurada não existe no computador.".to_string())?;
  Ok(root)
}

#[cfg(target_os = "windows")]
fn windows_compatible_path(path: &Path) -> PathBuf {
  let text = path.to_string_lossy();
  if let Some(unc_path) = text.strip_prefix(r"\\?\UNC\") {
    PathBuf::from(format!(r"\\{}", unc_path))
  } else if let Some(dos_path) = text.strip_prefix(r"\\?\") {
    PathBuf::from(dos_path)
  } else {
    path.to_path_buf()
  }
}

#[tauri::command]
fn open_library_file(relative_path: String) -> Result<(), String> {
  let file = library_file(&relative_path)?;
  #[cfg(target_os = "windows")]
  let status = {
    let file = windows_compatible_path(&file);
    let mut vlc_candidates = Vec::new();
    if let Ok(program_files) = std::env::var("ProgramFiles") {
      vlc_candidates.push(PathBuf::from(program_files).join("VideoLAN/VLC/vlc.exe"));
    }
    if let Ok(program_files_x86) = std::env::var("ProgramFiles(x86)") {
      vlc_candidates.push(PathBuf::from(program_files_x86).join("VideoLAN/VLC/vlc.exe"));
    }
    if let Ok(local_app_data) = std::env::var("LOCALAPPDATA") {
      vlc_candidates.push(PathBuf::from(local_app_data).join("Programs/VLC/vlc.exe"));
    }
    let vlc = vlc_candidates.into_iter().find(|candidate| candidate.is_file())
      .unwrap_or_else(|| PathBuf::from("vlc.exe"));
    Command::new(vlc).arg(file).status()
  };
  #[cfg(not(target_os = "windows"))]
  let status = Command::new("open").args(["-a", "VLC"]).arg(&file).status();
  let status = status
    .map_err(|_| "O VLC não está instalado ou não pôde ser iniciado.".to_string())?;
  if status.success() { Ok(()) } else { Err("O VLC não pôde abrir o arquivo selecionado.".to_string()) }
}

#[tauri::command]
fn reveal_library_file(relative_path: String) -> Result<(), String> {
  let file = library_file(&relative_path)?;
  #[cfg(target_os = "windows")]
  let status = Command::new("explorer.exe").arg(format!("/select,{}", file.display())).status();
  #[cfg(not(target_os = "windows"))]
  let status = Command::new("open").arg("-R").arg(&file).status();
  let status = status
    .map_err(|_| "O gerenciador de arquivos não pôde ser iniciado.".to_string())?;
  if status.success() { Ok(()) } else { Err("O gerenciador de arquivos não pôde revelar o arquivo selecionado.".to_string()) }
}

#[tauri::command]
fn relative_library_directory(path: String) -> Result<String, String> {
  let root = library_root()?;
  let selected = Path::new(&path).canonicalize()
    .map_err(|_| "A pasta escolhida não está disponível.".to_string())?;
  if !selected.is_dir() || !selected.starts_with(&root) {
    return Err("Escolha uma pasta dentro da sua Biblioteca.".to_string());
  }
  let relative = selected.strip_prefix(&root)
    .map_err(|_| "Escolha uma pasta dentro da sua Biblioteca.".to_string())?;
  if relative.as_os_str().is_empty() {
    return Err("Escolha uma subpasta dentro da sua Biblioteca.".to_string());
  }
  Ok(relative.to_string_lossy().replace('\\', "/"))
}

#[tauri::command]
fn open_external_url(url: String) -> Result<(), String> {
  let valid = url.starts_with("https://www.youtube.com/")
    || url.starts_with("https://youtu.be/")
    || url.starts_with("https://youtube.com/");
  if !valid {
    return Err("O link solicitado não é permitido.".to_string());
  }
  #[cfg(target_os = "windows")]
  let status = Command::new("cmd").args(["/C", "start", "", &url]).status();
  #[cfg(not(target_os = "windows"))]
  let status = Command::new("open").arg(url).status();
  let status = status
    .map_err(|_| "Não foi possível abrir o navegador.".to_string())?;
  if status.success() { Ok(()) } else { Err("Não foi possível abrir o navegador.".to_string()) }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
  tauri::Builder::default()
    .invoke_handler(tauri::generate_handler![open_library_file, reveal_library_file, relative_library_directory, open_external_url])
    .plugin(tauri_plugin_dialog::init())
    .setup(|app| {
      #[cfg(target_os = "macos")]
      {
        let window = app.get_webview_window("main")
          .ok_or("A janela principal não está disponível.")?;
        window.set_title_bar_style(tauri::TitleBarStyle::Overlay)?;
        window.set_decorations(true)?;
        window.set_title("")?;
      }
      if cfg!(debug_assertions) {
        app.handle().plugin(
          tauri_plugin_log::Builder::default()
            .level(log::LevelFilter::Info)
            .build(),
        )?;
      }
      Ok(())
    })
    .run(tauri::generate_context!())
    .expect("error while running tauri application");
}
