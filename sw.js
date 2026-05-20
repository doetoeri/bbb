self.addEventListener('notificationclick', function(e) {
  e.notification.close();
  e.waitUntil(clients.matchAll({ type: 'window' }).then(function(list) {
    for (var c of list) {
      if (c.focus) return c.focus();
    }
    if (clients.openWindow) return clients.openWindow('./');
  }));
});
