/**
 * On phones, list tables (.act-table) are shown as cards, one per row. Each cell gets a data-label with its column
 * heading so CSS can print "When", "About", ... next to the value. Runs whenever the page changes.
 */
export function watchTableLabels() {
  const label = () => {
    document.querySelectorAll('table.act-table').forEach((table) => {
      const heads = [...table.querySelectorAll('thead th')].map((th) => th.textContent.trim());
      if (!heads.length) return;
      table.querySelectorAll('tbody tr').forEach((tr) => {
        const cells = [...tr.children];
        if (cells.length !== heads.length) return; // "nothing found" rows span all columns
        cells.forEach((td, i) => { if (td.dataset.label !== heads[i]) td.dataset.label = heads[i]; });
      });
    });
  };
  let queued = false;
  const observer = new MutationObserver(() => {
    if (queued) return;
    queued = true;
    requestAnimationFrame(() => { queued = false; label(); });
  });
  observer.observe(document.body, { childList: true, subtree: true });
  label();
}
