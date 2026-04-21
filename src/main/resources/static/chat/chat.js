var currentUser = localStorage.getItem("chatapp_username") || "";
var uiMode = "all";
var activeGroupId = null;
var activePeer = null;
var socket = null;
var syncTimer = null;
var mediaRecorder = null;
var audioChunks = [];
var isRecording = false;
var currentViewerRole = "member";
var currentGroupMembers = [];

var currentUserEl = document.getElementById("current-user");
var groupFeedbackEl = document.getElementById("group-feedback");
var groupListEl = document.getElementById("group-list");
var allGroupListEl = document.getElementById("all-group-list");
var allFriendListEl = document.getElementById("all-friend-list");
var groupNameEl = document.getElementById("chat-group-name");
var groupCodeEl = document.getElementById("chat-group-code");
var messagesEl = document.getElementById("messages");
var newGroupInput = document.getElementById("new-group-name");
var joinGroupInput = document.getElementById("join-group-code");
var messageInput = document.getElementById("message-input");
var fileInput = document.getElementById("file-input");
var emojiPicker = document.getElementById("emoji-picker");
var voiceBtn = document.getElementById("voice-btn");
var leaveGroupBtn = document.getElementById("leave-group-btn");
var friendFeedbackEl = document.getElementById("friend-feedback");
var friendListEl = document.getElementById("friend-list");
var friendUsernameInput = document.getElementById("friend-username-input");
var renameGroupBtn = document.getElementById("rename-group-btn");
var memberRoleBadge = document.getElementById("member-role-badge");
var groupManageToolsEl = document.getElementById("group-manage-tools");
var memberListEl = document.getElementById("member-list");
var inviteUsernameInput = document.getElementById("invite-username-input");

if (!currentUser) {
  window.location.href = "../auth/login.html";
}

currentUserEl.textContent = currentUser;

function updateLeaveButtonVisibility() {
  var show = !!(activeGroupId && !activePeer && (uiMode === "groups" || uiMode === "all"));
  leaveGroupBtn.style.display = show ? "" : "none";
  renameGroupBtn.style.display = show ? "" : "none";
}

function doLogout() {
  if (!window.confirm("Bạn có chắc muốn đăng xuất?")) {
    return;
  }
  localStorage.removeItem("chatapp_username");
  window.location.href = "../auth/login.html";
}

document.getElementById("logout-btn").addEventListener("click", doLogout);
document.getElementById("logout-btn-nav").addEventListener("click", doLogout);

function setFeedback(message, isSuccess) {
  groupFeedbackEl.textContent = message || "";
  groupFeedbackEl.style.color = isSuccess ? "#1f8f47" : "#cc3d3d";
}

function setFriendFeedback(message, isSuccess) {
  friendFeedbackEl.textContent = message || "";
  friendFeedbackEl.style.color = isSuccess ? "#1f8f47" : "#cc3d3d";
}

function dmChannelKey() {
  if (!activePeer) {
    return "";
  }
  return [currentUser, activePeer].sort().join("|");
}

function isDmChat() {
  return !!activePeer && !activeGroupId;
}

function isGroupChat() {
  return !!activeGroupId && !activePeer;
}

function isGroupAdmin() {
  return currentViewerRole === "admin";
}

function api(path, method, body) {
  return fetch(path, {
    method: method || "GET",
    headers: { "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined
  }).then(function (response) {
    return response.text().then(function (text) {
      var data;
      if (!text || !String(text).trim()) {
        data = { ok: response.ok, message: response.statusText || "" };
      } else {
        try {
          data = JSON.parse(text);
        } catch (ignore) {
          data = {
            ok: false,
            message: "Phản hồi không phải JSON (HTTP " + response.status + "). Xem log server."
          };
        }
      }
      return { status: response.status, data: data };
    });
  });
}

function connectWebSocket() {
  var protocol = window.location.protocol === "https:" ? "wss://" : "ws://";
  socket = new WebSocket(protocol + window.location.host + "/ws/chat");
  socket.addEventListener("open", function () {
    refreshSocketRoom();
  });
  socket.addEventListener("message", function (event) {
    handleSocketMessage(event.data);
  });
  socket.addEventListener("close", function () {
    setTimeout(connectWebSocket, 1000);
  });
}

function refreshSocketRoom() {
  if (!socket || socket.readyState !== WebSocket.OPEN) {
    return;
  }
  if (isGroupChat()) {
    joinActiveGroupSocket();
  } else if (isDmChat()) {
    joinDmSocket();
  }
}

function handleSocketMessage(raw) {
  var payload;
  try {
    payload = JSON.parse(raw);
  } catch (e) {
    return;
  }

  if (payload.type === "error") {
    if (isDmChat()) {
      setFriendFeedback(payload.message || "Lỗi", false);
    } else {
      setFeedback(payload.message || "Lỗi WebSocket", false);
    }
    return;
  }
  if (payload.type === "history") {
    if (isGroupChat() && payload.groupId === activeGroupId) {
      renderMessages(payload.messages || []);
    }
    return;
  }
  if (payload.type === "message") {
    if (isGroupChat() && payload.groupId === activeGroupId && payload.message) {
      appendMessage(payload.message);
    }
    return;
  }
  if (payload.type === "dm_history") {
    if (isDmChat() && activePeer === payload.peer && payload.channelKey === dmChannelKey()) {
      renderMessages(payload.messages || []);
    }
    return;
  }
  if (payload.type === "dm_message") {
    if (isDmChat() && payload.channelKey === dmChannelKey() && payload.message) {
      appendMessage(payload.message);
    }
  }
}

function joinActiveGroupSocket() {
  if (!socket || socket.readyState !== WebSocket.OPEN || !activeGroupId) {
    return;
  }
  socket.send(JSON.stringify({
    type: "join",
    username: currentUser,
    groupId: String(activeGroupId)
  }));
}

function joinDmSocket() {
  if (!socket || socket.readyState !== WebSocket.OPEN || !activePeer) {
    return;
  }
  socket.send(JSON.stringify({
    type: "join_dm",
    username: currentUser,
    peer: activePeer
  }));
}

function sendMessageSocket(content) {
  if (!socket || socket.readyState !== WebSocket.OPEN) {
    if (isDmChat()) {
      setFriendFeedback("Socket đang kết nối lại…", false);
    } else {
      setFeedback("Socket đang kết nối lại…", false);
    }
    return false;
  }
  socket.send(JSON.stringify({
    type: "send",
    content: content
  }));
  return true;
}

function setTab(tab) {
  uiMode = tab;
  document.querySelectorAll(".tab-btn").forEach(function (b) {
    b.classList.toggle("active", b.getAttribute("data-tab") === tab);
  });
  document.getElementById("panel-all").classList.toggle("hidden", tab !== "all");
  document.getElementById("panel-groups").classList.toggle("hidden", tab !== "groups");
  document.getElementById("panel-friends").classList.toggle("hidden", tab !== "friends");
  updateLeaveButtonVisibility();

  if (tab === "groups") {
    activePeer = null;
    loadGroups().then(function () {
      refreshSocketRoom();
      loadGroupMembers();
    });
  } else if (tab === "friends") {
    activeGroupId = null;
    currentGroupMembers = [];
    currentViewerRole = "member";
    renderMemberList();
    if (isDmChat()) {
      groupNameEl.textContent = "Chat với " + activePeer;
      groupCodeEl.textContent = "Tin nhắn riêng";
    } else {
      groupNameEl.textContent = "Bạn bè";
      groupCodeEl.textContent = "Thêm bạn hoặc chọn tên để nhắn";
      renderMessages([]);
    }
    loadFriends().then(function () {
      refreshSocketRoom();
      if (isDmChat()) {
        loadDmMessagesApi();
      }
    });
  } else {
    Promise.all([loadGroups(), loadFriends()]).then(function () {
      refreshSocketRoom();
      if (isGroupChat()) {
        loadMessagesApi();
        loadGroupMembers();
      } else if (isDmChat()) {
        loadDmMessagesApi();
        currentGroupMembers = [];
        currentViewerRole = "member";
        renderMemberList();
      }
    });
  }
}

document.querySelectorAll(".tab-btn").forEach(function (btn) {
  btn.addEventListener("click", function () {
    setTab(btn.getAttribute("data-tab"));
  });
});

function loadFriends() {
  return api("/api/friends?username=" + encodeURIComponent(currentUser), "GET")
    .then(function (result) {
      if (!result.data.ok) {
        setFriendFeedback(result.data.message || "Không tải danh sách bạn", false);
        return;
      }
      var friends = result.data.friends || [];
      renderFriends(friends);
      if (uiMode === "all") {
        renderAllFriends(friends);
      }
    }).catch(function () {
      setFriendFeedback("Không kết nối máy chủ", false);
    });
}

function fillFriendList(el, friends) {
  el.innerHTML = "";
  if (!friends || friends.length === 0) {
    el.innerHTML = '<p class="group-feedback">Chưa có bạn. Thêm bằng tên đăng nhập.</p>';
    return;
  }
  friends.forEach(function (name) {
    var item = document.createElement("div");
    item.className = "room-item" + (name === activePeer && !activeGroupId ? " active" : "");
    item.innerHTML =
      '<div class="avatar">' + name.charAt(0).toUpperCase() + "</div>" +
      '<div class="meta"><h4>' + escapeHtml(name) + "</h4><p>Nhắn tin riêng</p></div>" +
      "<span></span>";
    item.addEventListener("click", function () {
      activeGroupId = null;
      activePeer = name;
      currentGroupMembers = [];
      currentViewerRole = "member";
      leaveGroupBtn.disabled = true;
      updateLeaveButtonVisibility();
      renderMessages([]);
      renderMemberList();
      groupNameEl.textContent = "Chat với " + name;
      groupCodeEl.textContent = "Tin nhắn riêng";
      refreshSocketRoom();
      loadDmMessagesApi();
      if (uiMode === "all") {
        loadGroups();
        loadFriends();
      } else if (uiMode === "friends") {
        loadFriends();
      } else {
        setTab("friends");
      }
    });
    el.appendChild(item);
  });
}

function renderFriends(friends) {
  fillFriendList(friendListEl, friends);
}

function renderAllFriends(friends) {
  fillFriendList(allFriendListEl, friends);
}

document.getElementById("add-friend-btn").addEventListener("click", function () {
  var friend = friendUsernameInput.value.trim();
  if (!friend) {
    setFriendFeedback("Nhập tên đăng nhập bạn bè", false);
    return;
  }
  api("/api/friends", "POST", { username: currentUser, friendUsername: friend })
    .then(function (result) {
      if (!result.data.ok) {
        setFriendFeedback(result.data.message || "Thêm bạn thất bại", false);
        return;
      }
      friendUsernameInput.value = "";
      setFriendFeedback(result.data.message || "Đã thêm", true);
      loadFriends();
      if (uiMode === "all") {
        loadGroups();
      }
    }).catch(function (err) {
      var msg = (err && err.message) ? err.message : "Không kết nối máy chủ";
      setFriendFeedback(msg, false);
    });
});

function fillGroupList(el, groups) {
  el.innerHTML = "";
  if (!groups || groups.length === 0) {
    el.innerHTML = '<p class="group-feedback">Bạn chưa có group nào</p>';
    return;
  }

  groups.forEach(function (group) {
    var item = document.createElement("div");
    item.className = "room-item" + (group.id === activeGroupId && !activePeer ? " active" : "");
    item.innerHTML =
      '<div class="avatar">' + group.name.charAt(0).toUpperCase() + "</div>" +
      '<div class="meta"><h4>' + group.name + "</h4><p>Mã group: " + group.code + "</p></div>" +
      "<span>#" + group.id + "</span>";
    item.addEventListener("click", function () {
      activePeer = null;
      activeGroupId = group.id;
      leaveGroupBtn.disabled = false;
      updateLeaveButtonVisibility();
      renderMessages([]);
      groupNameEl.textContent = group.name;
      groupCodeEl.textContent = "Mã group: " + group.code;
      refreshSocketRoom();
      loadMessagesApi();
      loadGroupMembers();
      if (uiMode === "all") {
        loadGroups();
        loadFriends();
      } else if (uiMode === "groups") {
        loadGroups();
      } else {
        setTab("groups");
      }
    });
    el.appendChild(item);
  });
}

function renderGroups(groups) {
  fillGroupList(groupListEl, groups);
}

function renderAllGroups(groups) {
  fillGroupList(allGroupListEl, groups);
}

function renderMemberList() {
  memberListEl.innerHTML = "";
  if (!isGroupChat()) {
    memberRoleBadge.textContent = "-";
    groupManageToolsEl.classList.add("hidden");
    memberListEl.innerHTML = '<p class="group-feedback">Chọn nhóm để xem thành viên.</p>';
    return;
  }
  memberRoleBadge.textContent = currentViewerRole || "member";
  groupManageToolsEl.classList.toggle("hidden", !isGroupAdmin());
  if (!currentGroupMembers || currentGroupMembers.length === 0) {
    memberListEl.innerHTML = '<p class="group-feedback">Nhóm chưa có thành viên.</p>';
    return;
  }
  currentGroupMembers.forEach(function (m) {
    var item = document.createElement("div");
    item.className = "member-item";
    var role = (m.role || "member").toLowerCase();
    var escapedUser = escapeHtml(m.username || "");
    item.innerHTML = '<div class="member-top"><strong>' + escapedUser + '</strong><span class="role-chip">' + escapeHtml(role) + '</span></div>';
    if (isGroupAdmin() && role !== "owner" && m.username !== currentUser) {
      var actions = document.createElement("div");
      actions.className = "member-actions";
      var roleBtn = document.createElement("button");
      roleBtn.type = "button";
      roleBtn.textContent = role === "admin" ? "Hạ quyền" : "Lên admin";
      roleBtn.addEventListener("click", function () {
        var nextRole = role === "admin" ? "member" : "admin";
        api("/api/groups/" + activeGroupId + "/role", "POST", {
          username: currentUser,
          targetUsername: m.username,
          role: nextRole
        }).then(function (result) {
          setFeedback(result.data.message || "Cập nhật role", !!result.data.ok);
          if (result.data.ok) {
            loadGroupMembers();
          }
        });
      });
      actions.appendChild(roleBtn);

      var kickBtn = document.createElement("button");
      kickBtn.type = "button";
      kickBtn.className = "danger";
      kickBtn.textContent = "Kick";
      kickBtn.addEventListener("click", function () {
        if (!window.confirm("Kick " + m.username + " khỏi nhóm?")) {
          return;
        }
        api("/api/groups/" + activeGroupId + "/kick", "POST", {
          username: currentUser,
          targetUsername: m.username
        }).then(function (result) {
          setFeedback(result.data.message || "Đã kick", !!result.data.ok);
          if (result.data.ok) {
            loadGroupMembers();
            loadGroups();
          }
        });
      });
      actions.appendChild(kickBtn);
      item.appendChild(actions);
    }
    memberListEl.appendChild(item);
  });
}

function loadGroupMembers() {
  if (!isGroupChat()) {
    currentGroupMembers = [];
    currentViewerRole = "member";
    renderMemberList();
    return Promise.resolve();
  }
  return api("/api/groups/" + activeGroupId + "/members?username=" + encodeURIComponent(currentUser), "GET")
    .then(function (result) {
      if (!result.data.ok) {
        return;
      }
      currentGroupMembers = result.data.members || [];
      currentViewerRole = result.data.viewerRole || "member";
      renderMemberList();
    });
}

function renderMessages(messages) {
  messagesEl.innerHTML = "";
  if (!messages || messages.length === 0) {
    messagesEl.innerHTML = '<p class="empty-msg">Chưa có tin nhắn. Hãy gửi tin đầu tiên.</p>';
    return;
  }

  messages.forEach(function (msg) {
    appendMessage(msg);
  });
  messagesEl.scrollTop = messagesEl.scrollHeight;
}

function appendMessage(msg) {
  var row = document.createElement("div");
  var side = msg.username === currentUser ? "right" : "left";
  row.className = "msg " + side;
  var body = "<strong>" + escapeHtml(msg.username) + ":</strong> ";

  var mt = msg.messageType || msg.message_type || "text";
  var fileUrl = msg.fileUrl || msg.file_url;
  var fileName = msg.fileName || msg.file_name;

  if ((mt === "file" || mt === "image") && fileUrl) {
    if (mt === "image") {
      var safeAlt = escapeHtml(fileName || "Ảnh");
      body += '<a href="' + fileUrl + '" target="_blank" rel="noopener"><img class="chat-img" src="' + fileUrl + '" alt="' + safeAlt + '"></a>';
    } else {
      body += '<a href="' + fileUrl + '" target="_blank" rel="noopener">' + escapeHtml(fileName || "Tải file") + "</a>";
    }
  } else if (mt === "voice" && fileUrl) {
    body += '<audio controls src="' + fileUrl + '"></audio>';
  } else {
    body += escapeHtml(msg.content || "");
  }

  row.innerHTML = "<p>" + body + "</p>" + "<small>" + (msg.createdAt || msg.created_at || "") + "</small>";
  messagesEl.appendChild(row);
  messagesEl.scrollTop = messagesEl.scrollHeight;
}

function escapeHtml(text) {
  return text
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function syncActiveGroupWithList(groups) {
  if (!groups || groups.length === 0) {
    activeGroupId = null;
    groupNameEl.textContent = "Chưa chọn group";
    groupCodeEl.textContent = "Tạo hoặc join group để bắt đầu chat";
    renderMessages([]);
    leaveGroupBtn.disabled = true;
    updateLeaveButtonVisibility();
    return;
  }
  leaveGroupBtn.disabled = false;
  var still = groups.some(function (g) { return g.id === activeGroupId; });
  if (!still) {
    activeGroupId = groups[0].id;
  }
  var cur = groups.find(function (g) { return g.id === activeGroupId; });
  if (cur) {
    groupNameEl.textContent = cur.name;
    groupCodeEl.textContent = "Mã group: " + cur.code;
  }
}

function loadGroups() {
  return api("/api/groups?username=" + encodeURIComponent(currentUser), "GET")
    .then(function (result) {
      if (!result.data.ok) {
        setFeedback(result.data.message || "Không tải được danh sách group", false);
        return;
      }
      var groups = result.data.groups || [];
      if (uiMode === "groups") {
        syncActiveGroupWithList(groups);
        renderGroups(groups);
        if (activeGroupId && !activePeer) {
          joinActiveGroupSocket();
          loadMessagesApi();
        }
      } else {
        renderGroups(groups);
        if (uiMode === "all" && activeGroupId && !activePeer) {
          var cur = groups.find(function (g) { return g.id === activeGroupId; });
          if (cur) {
            groupNameEl.textContent = cur.name;
            groupCodeEl.textContent = "Mã group: " + cur.code;
          }
        }
      }
      if (uiMode === "all") {
        renderAllGroups(groups);
      }
      updateLeaveButtonVisibility();
      if (isGroupChat()) {
        loadGroupMembers();
      }
    }).catch(function () {
      setFeedback("Không kết nối được máy chủ", false);
    });
}

function loadMessagesApi() {
  if (!isGroupChat()) {
    return;
  }
  api("/api/groups/" + activeGroupId + "/messages?username=" + encodeURIComponent(currentUser), "GET")
    .then(function (result) {
      if (!result.data.ok) {
        return;
      }
      renderMessages(result.data.messages || []);
    });
}

function loadDmMessagesApi() {
  if (!isDmChat()) {
    return;
  }
  api("/api/dm/messages?username=" + encodeURIComponent(currentUser) + "&peer=" + encodeURIComponent(activePeer), "GET")
    .then(function (result) {
      if (!result.data.ok) {
        return;
      }
      renderMessages(result.data.messages || []);
    });
}

function uploadBlob(fileBlob, fileName) {
  if (isDmChat()) {
    var formData = new FormData();
    formData.append("username", currentUser);
    formData.append("peer", activePeer);
    formData.append("file", fileBlob, fileName);
    fetch("/api/dm/files", {
      method: "POST",
      body: formData
    }).then(function (response) {
      return response.json().then(function (data) {
        return { status: response.status, data: data };
      });
    }).then(function (result) {
      if (!result.data.ok) {
        setFriendFeedback(result.data.message || "Upload thất bại", false);
        return;
      }
      setFriendFeedback("Đã gửi file", true);
      loadDmMessagesApi();
    }).catch(function () {
      setFriendFeedback("Không kết nối máy chủ", false);
    });
    return;
  }

  if (!activeGroupId) {
    setFeedback("Bạn chưa chọn group", false);
    return;
  }
  var formData = new FormData();
  formData.append("username", currentUser);
  formData.append("file", fileBlob, fileName);
  fetch("/api/groups/" + activeGroupId + "/files", {
    method: "POST",
    body: formData
  }).then(function (response) {
    return response.json().then(function (data) {
      return { status: response.status, data: data };
    });
  }).then(function (result) {
    if (!result.data.ok) {
      setFeedback(result.data.message || "Upload thất bại", false);
      return;
    }
    setFeedback("Đã gửi " + (result.data.messageType === "voice" ? "voice" : "file"), true);
    loadMessagesApi();
  }).catch(function () {
    setFeedback("Không kết nối được máy chủ", false);
  });
}

document.getElementById("create-group-btn").addEventListener("click", function () {
  var groupName = newGroupInput.value.trim();
  if (!groupName) {
    setFeedback("Nhập tên group trước khi tạo", false);
    return;
  }
  api("/api/groups/create", "POST", { username: currentUser, groupName: groupName })
    .then(function (result) {
      if (!result.data.ok) {
        setFeedback(result.data.message || "Tạo group thất bại", false);
        return;
      }
      newGroupInput.value = "";
      setFeedback("Tạo group thành công. Mã: " + result.data.group.code, true);
      activePeer = null;
      activeGroupId = result.data.group.id;
      groupNameEl.textContent = result.data.group.name;
      groupCodeEl.textContent = "Mã group: " + result.data.group.code;
      updateLeaveButtonVisibility();
      loadGroups().then(function () {
        refreshSocketRoom();
        loadMessagesApi();
      });
    }).catch(function () {
      setFeedback("Không kết nối được máy chủ", false);
    });
});

document.getElementById("invite-member-btn").addEventListener("click", function () {
  var target = inviteUsernameInput.value.trim();
  if (!isGroupChat()) {
    setFeedback("Chọn nhóm trước khi mời", false);
    return;
  }
  if (!target) {
    setFeedback("Nhập username cần mời", false);
    return;
  }
  api("/api/groups/" + activeGroupId + "/invite", "POST", {
    username: currentUser,
    targetUsername: target
  }).then(function (result) {
    setFeedback(result.data.message || "Đã mời", !!result.data.ok);
    if (result.data.ok) {
      inviteUsernameInput.value = "";
      loadGroupMembers();
      loadGroups();
    }
  }).catch(function () {
    setFeedback("Không kết nối được máy chủ", false);
  });
});

renameGroupBtn.addEventListener("click", function () {
  if (!isGroupChat()) {
    setFeedback("Chọn nhóm trước khi đổi tên", false);
    return;
  }
  if (!isGroupAdmin()) {
    setFeedback("Chỉ admin mới đổi tên nhóm", false);
    return;
  }
  var nextName = window.prompt("Tên nhóm mới:");
  if (!nextName) {
    return;
  }
  api("/api/groups/" + activeGroupId + "/rename", "POST", {
    username: currentUser,
    groupName: nextName
  }).then(function (result) {
    setFeedback(result.data.message || "Đã đổi tên nhóm", !!result.data.ok);
    if (result.data.ok) {
      loadGroups();
    }
  }).catch(function () {
    setFeedback("Không kết nối được máy chủ", false);
  });
});

leaveGroupBtn.addEventListener("click", function () {
  if (!activeGroupId) {
    return;
  }
  if (!window.confirm("Bạn có chắc muốn rời nhóm này?")) {
    return;
  }
  api("/api/groups/" + activeGroupId + "/leave", "POST", { username: currentUser })
    .then(function (result) {
      if (!result.data.ok) {
        setFeedback(result.data.message || "Rời nhóm thất bại", false);
        return;
      }
      setFeedback("Đã rời nhóm", true);
      activeGroupId = null;
      currentGroupMembers = [];
      currentViewerRole = "member";
      groupNameEl.textContent = "Chưa chọn group";
      groupCodeEl.textContent = "Tạo hoặc join group để bắt đầu chat";
      renderMessages([]);
      renderMemberList();
      updateLeaveButtonVisibility();
      loadGroups();
      if (uiMode === "all") {
        loadFriends();
      }
    }).catch(function () {
      setFeedback("Không kết nối được máy chủ", false);
    });
});

document.getElementById("join-group-btn").addEventListener("click", function () {
  var groupCode = joinGroupInput.value.trim().toUpperCase();
  if (!groupCode) {
    setFeedback("Nhập mã group để join", false);
    return;
  }
  api("/api/groups/join", "POST", { username: currentUser, groupCode: groupCode })
    .then(function (result) {
      if (!result.data.ok) {
        setFeedback(result.data.message || "Join group thất bại", false);
        return;
      }
      joinGroupInput.value = "";
      setFeedback("Join group thành công", true);
      if (uiMode === "groups") {
        setTab("groups");
      } else {
        loadGroups();
        if (uiMode === "all") {
          loadFriends();
        }
      }
    }).catch(function () {
      setFeedback("Không kết nối được máy chủ", false);
    });
});

document.getElementById("send-message-btn").addEventListener("click", function () {
  var content = messageInput.value.trim();
  if (!content) {
    return;
  }
  if (isDmChat()) {
    if (sendMessageSocket(content)) {
      messageInput.value = "";
    }
    return;
  }
  if (isGroupChat()) {
    if (sendMessageSocket(content)) {
      messageInput.value = "";
    }
    return;
  }
  if (uiMode === "friends") {
    setFriendFeedback("Chọn một bạn để chat", false);
    return;
  }
  if (uiMode === "groups") {
    setFeedback("Bạn chưa chọn group", false);
    return;
  }
  setFeedback("Chọn một nhóm hoặc bạn bè để chat", false);
});

messageInput.addEventListener("keydown", function (event) {
  if (event.key === "Enter") {
    event.preventDefault();
    document.getElementById("send-message-btn").click();
  }
});

document.getElementById("attach-file-btn").addEventListener("click", function () {
  if (isDmChat()) {
    fileInput.click();
    return;
  }
  if (isGroupChat()) {
    fileInput.click();
    return;
  }
  if (uiMode === "friends") {
    setFriendFeedback("Chọn bạn để gửi file", false);
    return;
  }
  if (uiMode === "groups") {
    setFeedback("Bạn chưa chọn group", false);
    return;
  }
  setFeedback("Chọn nhóm hoặc bạn bè để gửi file", false);
});

fileInput.addEventListener("change", function () {
  if (!fileInput.files || fileInput.files.length === 0) {
    return;
  }
  var file = fileInput.files[0];
  uploadBlob(file, file.name || "upload.bin");
  fileInput.value = "";
});

var emojis = ["😀", "😂", "😍", "😎", "👍", "❤️", "🎉", "🔥"];
emojiPicker.innerHTML = emojis.map(function (emoji) {
  return '<button type="button" data-emoji="' + emoji + '">' + emoji + "</button>";
}).join("");

emojiPicker.addEventListener("click", function (event) {
  var target = event.target;
  var emoji = target.getAttribute("data-emoji");
  if (!emoji) {
    return;
  }
  messageInput.value += emoji;
  messageInput.focus();
});

document.getElementById("emoji-btn").addEventListener("click", function () {
  emojiPicker.classList.toggle("hidden");
});

voiceBtn.addEventListener("click", async function () {
  if (!isDmChat() && !isGroupChat()) {
    if (uiMode === "friends") {
      setFriendFeedback("Chọn bạn để gửi voice", false);
    } else if (uiMode === "all") {
      setFeedback("Chọn nhóm hoặc bạn để gửi voice", false);
    } else {
      setFeedback("Bạn chưa chọn group", false);
    }
    return;
  }

  if (!isRecording) {
    try {
      var stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      mediaRecorder = new MediaRecorder(stream);
      audioChunks = [];
      mediaRecorder.addEventListener("dataavailable", function (e) {
        if (e.data && e.data.size > 0) {
          audioChunks.push(e.data);
        }
      });
      mediaRecorder.addEventListener("stop", function () {
        var blob = new Blob(audioChunks, { type: "audio/webm" });
        uploadBlob(blob, "voice.webm");
        stream.getTracks().forEach(function (track) { track.stop(); });
      });
      mediaRecorder.start();
      isRecording = true;
      voiceBtn.textContent = "⏹";
      if (isDmChat()) {
        setFriendFeedback("Đang ghi âm… bấm lại để gửi", true);
      } else {
        setFeedback("Đang ghi âm… bấm lại để gửi", true);
      }
    } catch (e) {
      if (isDmChat()) {
        setFriendFeedback("Không truy cập microphone", false);
      } else {
        setFeedback("Không truy cập microphone", false);
      }
    }
  } else {
    isRecording = false;
    voiceBtn.textContent = "🎤";
    if (mediaRecorder && mediaRecorder.state !== "inactive") {
      mediaRecorder.stop();
    }
  }
});

connectWebSocket();
updateLeaveButtonVisibility();
renderMemberList();
setTab("all");
syncTimer = setInterval(function () {
  if (isGroupChat()) {
    loadMessagesApi();
  } else if (isDmChat()) {
    loadDmMessagesApi();
  }
}, 5000);
