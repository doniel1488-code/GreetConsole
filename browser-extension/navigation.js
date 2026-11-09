(() => {
  const tabs = Array.from(document.querySelectorAll('[data-view]'));
  const screens = Array.from(document.querySelectorAll('.screen'));

  tabs.forEach(tab => tab.addEventListener('click', () => {
    const target = tab.dataset.view;
    tabs.forEach(item => item.classList.toggle('active', item === tab));
    screens.forEach(screen => {
      const active = screen.id === target;
      screen.classList.toggle('active', active);
      screen.hidden = !active;
    });
  }));
})();