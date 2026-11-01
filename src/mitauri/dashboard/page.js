"use strict";

const POLL_MS = 5000;
const $ = (id) => document.getElementById(id);
let timer = null;
let lastGood = null;
let signedIn = false;

/* Everything is set as text: names and errors can come from outside. */
function node(tag, text, className) {
  const element = document.createElement(tag);
  if (text !== undefined && text !== null) element.textContent = text;
  if (className) element.className = className;
  return element;
}

function clock(seconds) {
  const when = new Date(seconds * 1000);
  const time = when.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
  if (when.toDateString() === new Date().toDateString()) return time;
  return when.toLocaleDateString([], { day: "2-digit", month: "2-digit" }) + " " + time;
}

function ago(seconds) {
  if (seconds < 90) return `${Math.round(seconds)} s ago`;
  if (seconds < 5400) return `${Math.round(seconds / 60)} min ago`;
  if (seconds < 129600) return `${Math.round(seconds / 3600)} h ago`;
  return `${Math.round(seconds / 86400)} days ago`;
}

function duration(seconds) {
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor(seconds % 86400 / 3600);
  const minutes = Math.floor(seconds % 3600 / 60);
  if (days) return `${days} d ${hours} h`;
  return hours ? `${hours} h ${minutes} min` : `${minutes} min`;
}

function facts(target, rows) {
  target.replaceChildren(...rows.flatMap(([label, value, className]) => {
    const dd = value instanceof Node ? node("dd") : node("dd", value, className);
    if (value instanceof Node) dd.append(value);
    return [node("dt", label), dd];
  }));
}

const STATE = {
  near: ["near", "good"], in_room: ["in room", "good"], nearby: ["nearby", ""],
  away: ["away", "dim"], unknown: ["unknown", "warn"],
};

function presenceRow(sensor, device, seen) {
  const [label, className] = STATE[seen.state] || [seen.state, ""];
  const row = node("tr");
  row.append(node("td", sensor), node("td", device), node("td", label, className));
  if (seen.state === "unknown") {
    const reason = node("td", seen.reason, "dim");
    reason.colSpan = 2;
    row.append(reason);
  } else {
    row.append(
      node("td", seen.metres_rough === undefined ? "–" : `~${seen.metres_rough} m`, "n"),
      node("td", seen.seen_seconds_ago === undefined ? "–" : ago(seen.seen_seconds_ago), "n"),
    );
  }
  return row;
}

function renderPresence(sensors) {
  const rows = [];
  for (const [sensor, devices] of Object.entries(sensors).sort()) {
    if (devices.state === "unknown") {
      rows.push(presenceRow(sensor, "–", devices));
      continue;
    }
    for (const [device, seen] of Object.entries(devices).sort()) rows.push(presenceRow(sensor, device, seen));
  }
  if (!rows.length) {
    const empty = node("td", "No sensors are set up.", "dim");
    empty.colSpan = 5;
    const row = node("tr");
    row.append(empty);
    rows.push(row);
  }
  $("presence").replaceChildren(...rows);
}

function locationLink(place) {
  const lat = place.latitude.toFixed(5);
  const lon = place.longitude.toFixed(5);
  const link = node("a", `${lat}, ${lon}`);
  link.href = `https://www.openstreetmap.org/?mlat=${lat}&mlon=${lon}#map=17/${lat}/${lon}`;
  link.rel = "noreferrer noopener";
  link.target = "_blank";
  return link;
}

function renderPhone(phone, now) {
  const place = phone.location;
  const rows = [phone.connected
    ? ["App", phone.since ? `connected for ${duration(now - phone.since)}` : "connected", "good"]
    : ["App", phone.gone_at ? `not connected, lost ${ago(now - phone.gone_at)}` : "not connected since the server started", "bad"]];
  if (place.latitude !== undefined) {
    rows.push(["Location", locationLink(place)]);
    if (place.accuracy_m !== undefined && place.accuracy_m !== null) rows.push(["Accuracy", `±${Math.round(place.accuracy_m)} m`]);
    rows.push(["Fix asked", ago(place.age_seconds)]);
  } else if (place.error) {
    rows.push(["Location", place.error, "bad"]);
  } else {
    rows.push(["Location", phone.connected ? "asking the phone…" : "unknown", "dim"]);
  }
  facts($("phone"), rows);
}

function renderWatch(watch) {
  const rows = [];
  if (watch.error) {
    rows.push(["Status", watch.error, "bad"]);
  } else if (watch.linked === undefined) {
    rows.push(["Status", "asking the phone…", "dim"]);
  } else if (!watch.linked) {
    rows.push(["Status", "not linked", "dim"]);
  } else {
    rows.push(["Link", watch.connected ? "connected" : "not connected", watch.connected ? "good" : "bad"]);
    if (watch.battery_percent !== undefined) {
      rows.push(["Battery", `${watch.battery_percent}%`, watch.battery_percent < 20 ? "bad" : ""]);
    }
  }
  if (watch.age_seconds !== undefined) rows.push(["Asked", ago(watch.age_seconds)]);
  facts($("watch"), rows);
}

function renderServer(server) {
  const rows = [["Up for", duration(server.uptime_seconds)]];
  if (server.claude !== undefined) rows.push(["Claude", server.claude ? "process running" : "no process yet"]);
  if (server.discord !== undefined) rows.push(["Discord", server.discord ? "connected" : "not connected", server.discord ? "good" : "bad"]);
  if (server.memory_mb !== undefined) rows.push(["Memory", `${server.memory_mb} MB`]);
  if (server.load !== undefined) rows.push(["Load", server.load]);
  rows.push(["Disk free", `${server.disk_free_gb} GB (${server.disk_free_percent}%)`, server.disk_free_percent < 10 ? "bad" : ""]);
  if (server.modules_off.length) rows.push(["Modules off", server.modules_off.join(", ")]);
  facts($("server"), rows);
}

function renderDevices(devices, reporters, now) {
  const rows = devices.map((device) => {
    const report = reporters[device.listener];
    const detail = device.listener
      ? `report-only${report ? `, reported ${ago(report.seconds_ago)}${report.listening ? "" : ", can't listen"}` : ", no report yet"}`
      : "full access";
    return [device.name, detail];
  });
  facts($("devices"), rows.length ? rows : [["None", "No device is paired."]]);
}

function renderProblems(problems) {
  if (!problems.length) {
    const row = node("tr");
    row.append(node("td", "None.", "dim"));
    $("problems").replaceChildren(row);
    return;
  }
  $("problems").replaceChildren(...problems.map((problem) => {
    const row = node("tr");
    row.append(node("td", clock(problem.time), "time"), node("td", problem.source, "source"), node("td", problem.text, "text"));
    return row;
  }));
}

function render(status) {
  const issues = status.issues;
  $("attention").hidden = !issues.length;
  $("issues").replaceChildren(...issues.map((issue) => node("li", issue)));
  $("summary").textContent = issues.length ? `${issues.length} need${issues.length === 1 ? "s" : ""} attention` : "all normal";
  $("summary").className = issues.length ? "bad" : "good";
  renderPresence(status.sensors);
  renderPhone(status.phone, status.now);
  renderWatch(status.watch);
  renderServer(status.server);
  renderDevices(status.devices, status.reporters, status.now);
  renderProblems(status.problems);
}

function showLogin() {
  signedIn = false;
  clearTimeout(timer);
  $("status").hidden = true;
  $("logout").hidden = true;
  $("login").hidden = false;
  $("summary").textContent = "";
  $("updated").textContent = "";
}

async function poll() {
  clearTimeout(timer);
  try {
    const response = await fetch("/dashboard/status");
    if (response.status === 401) return showLogin();
    if (!response.ok) throw new Error(String(response.status));
    render(await response.json());
    signedIn = true;
    lastGood = Date.now();
    $("login").hidden = true;
    $("status").hidden = false;
    $("logout").hidden = false;
    $("updated").textContent = `updated ${clock(lastGood / 1000)}`;
    $("updated").className = "";
  } catch {
    $("summary").textContent = "server not answering";
    $("summary").className = "bad";
    $("updated").textContent = lastGood ? `last answer ${clock(lastGood / 1000)}` : "";
  }
  /* A hidden tab stops polling, and with it the phone stops being asked for its location. */
  if (!document.hidden) timer = setTimeout(poll, POLL_MS);
}

async function login(code) {
  $("login-error").hidden = true;
  let response;
  try {
    response = await fetch("/dashboard/login", {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ code }),
    });
  } catch {
    response = null;
  }
  if (!response || !response.ok) {
    $("login").hidden = false;
    $("login-error").textContent = response && response.status === 429
      ? "Too many wrong tries. Wait 15 minutes."
      : "That code didn't work. Codes work once and expire after 10 minutes.";
    $("login-error").hidden = false;
    return;
  }
  $("code").value = "";
  poll();
}

$("login").addEventListener("submit", (event) => {
  event.preventDefault();
  login($("code").value.trim());
});

$("logout").addEventListener("click", async () => {
  await fetch("/dashboard/logout", { method: "POST" }).catch(() => {});
  showLogin();
});

document.addEventListener("visibilitychange", () => {
  if (document.hidden) clearTimeout(timer);
  else if (signedIn) poll();
});

/* The code in the link sits after the #, so it's never sent in a URL or kept in the address bar. */
const linked = location.hash.slice(1);
if (linked) {
  history.replaceState(null, "", location.pathname);
  login(linked);
} else {
  poll();
}
