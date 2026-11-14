import { Injectable } from '@angular/core';
import { invoke } from '@tauri-apps/api/core';
import { confirm } from '@tauri-apps/plugin-dialog';
import { open } from '@tauri-apps/plugin-dialog';

@Injectable({ providedIn: 'root' })
export class DesktopFileService {
  openInVlc(relativePath: string): Promise<void> {
    return invoke('open_library_file', { relativePath });
  }

  revealInFinder(relativePath: string): Promise<void> {
    return invoke('reveal_library_file', { relativePath });
  }

  openExternalUrl(url: string): Promise<void> {
    return invoke('open_external_url', { url });
  }

  confirmDeletion(title: string): Promise<boolean> {
    return confirm(this.deletionMessage(title), {
      title: 'Excluir arquivo',
      kind: 'warning',
      okLabel: 'Excluir',
      cancelLabel: 'Cancelar',
    });
  }

  deletionMessage(title: string): string {
    return `Excluir somente o arquivo local “${title}”? A transferência vinculada será cancelada e este arquivo sairá da biblioteca. Os outros arquivos do torrent serão mantidos. Esta ação não pode ser desfeita.`;
  }

  async chooseDownloadDirectory(): Promise<string | null> {
    const selection = await open({ directory: true, multiple: false, title: 'Escolha a pasta dos downloads' });
    if (!selection || Array.isArray(selection)) return null;
    return invoke<string>('relative_library_directory', { path: selection });
  }
}
