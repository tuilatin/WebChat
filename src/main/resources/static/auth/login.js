function toggleVisibility(inputId) {
  var input = document.getElementById(inputId);
  if (!input) return;
  input.type = input.type === "password" ? "text" : "password";
}

document.querySelectorAll(".toggle-btn").forEach(function (btn) {
  btn.addEventListener("click", function () {
    toggleVisibility(btn.getAttribute("data-target"));
  });
});

var panelTag = document.getElementById("panel-tag");
var loginView = document.getElementById("login-view");
var registerView = document.getElementById("register-view");
var showRegisterLink = document.getElementById("show-register");
var showLoginLink = document.getElementById("show-login");

var registerPassword = document.getElementById("register-password");
var registerRepassword = document.getElementById("register-repassword");
var errorText = document.getElementById("password-error");
var loginError = document.getElementById("login-error");
var registerError = document.getElementById("register-error");
var loginUsername = document.getElementById("login-username");
var loginPassword = document.getElementById("login-password");
var registerUsername = document.getElementById("register-username");
var loginForm = document.getElementById("login-view");
var registerForm = document.getElementById("register-view");

function showLogin() {
  panelTag.textContent = "Đăng nhập";
  loginView.classList.remove("hidden");
  registerView.classList.add("hidden");
  setMessage(loginError, "", false);
  setMessage(registerError, "", false);
}

function showRegister() {
  panelTag.textContent = "Đăng ký";
  loginView.classList.add("hidden");
  registerView.classList.remove("hidden");
  setMessage(loginError, "", false);
  setMessage(registerError, "", false);
}

showRegisterLink.addEventListener("click", function (event) {
  event.preventDefault();
  showRegister();
});

showLoginLink.addEventListener("click", function (event) {
  event.preventDefault();
  showLogin();
});

function validatePasswords() {
  var isMismatch = registerPassword.value !== registerRepassword.value;
  registerRepassword.classList.toggle("error", isMismatch);
  errorText.textContent = isMismatch ? "Mật khẩu không khớp" : "";
  return !isMismatch;
}

function setMessage(target, message, isSuccess) {
  target.textContent = message;
  target.classList.toggle("ok-msg", isSuccess);
}

function postJson(url, payload) {
  return fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  }).then(function (response) {
    return response.json().then(function (data) {
      return { status: response.status, data: data };
    });
  });
}

registerPassword.addEventListener("input", validatePasswords);
registerRepassword.addEventListener("input", validatePasswords);

loginForm.addEventListener("submit", function (event) {
  event.preventDefault();
  setMessage(loginError, "", false);

  postJson("/api/login", {
    username: loginUsername.value.trim(),
    password: loginPassword.value
  }).then(function (result) {
    if (!result.data.ok) {
      setMessage(loginError, result.data.message || "Đăng nhập thất bại", false);
      return;
    }
    localStorage.setItem("chatapp_username", loginUsername.value.trim());
    window.location.href = "../chat/index.html";
  }).catch(function () {
    setMessage(loginError, "Không kết nối được máy chủ", false);
  });
});

registerForm.addEventListener("submit", function (event) {
  setMessage(registerError, "", false);
  if (!validatePasswords()) {
    event.preventDefault();
    return;
  }
  event.preventDefault();

  postJson("/api/register", {
    username: registerUsername.value.trim(),
    password: registerPassword.value
  }).then(function (result) {
    if (!result.data.ok) {
      setMessage(registerError, result.data.message || "Đăng ký thất bại", false);
      return;
    }
    setMessage(registerError, "Đăng ký thành công, mời đăng nhập", true);
    showLogin();
    loginUsername.value = registerUsername.value.trim();
    loginPassword.value = "";
  }).catch(function () {
    setMessage(registerError, "Không kết nối được máy chủ", false);
  });
});
