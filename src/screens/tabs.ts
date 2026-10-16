import type {IconName} from '../components/ui/Icon';

export type TabName = 'touchpad' | 'media' | 'clipboard' | 'connection';

export const TABS: {name: TabName; label: string; icon: IconName}[] = [
  {name: 'touchpad', label: 'Touchpad', icon: 'touchpad'},
  {name: 'media', label: 'Mídia', icon: 'audio-lines'},
  {name: 'clipboard', label: 'Transferir', icon: 'clipboard'},
  {name: 'connection', label: 'Conexão', icon: 'monitor-smartphone'},
];
