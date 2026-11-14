import { isNativeApp } from './api.js';

/**
 * Set-up for the Android / iOS app (does nothing in a browser): marks the page so CSS can adapt, colours the
 * status bar, hides the splash screen, and makes Android's back button close an open dialog first, then put the
 * app in the background.
 */
export async function initNativeApp() {
  if (!isNativeApp()) return;
  document.documentElement.classList.add('native-app');
  document.documentElement.classList.add(`native-${window.Capacitor.getPlatform()}`);
  const [{ StatusBar, Style }, { SplashScreen }, { App }] = await Promise.all([
    import('@capacitor/status-bar'), import('@capacitor/splash-screen'), import('@capacitor/app'),
  ]);
  try { await StatusBar.setStyle({ style: Style.Dark }); } catch { /* not on every platform */ }
  SplashScreen.hide().catch(() => {});
  App.addListener('backButton', () => {
    if (document.querySelector('.modal-backdrop, [role="dialog"]')) {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    } else {
      App.minimizeApp();
    }
  });
}
