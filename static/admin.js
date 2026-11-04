'use strict';
const $ = id => document.getElementById(id);
const escapeHTML = text => String(text).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let dashboard = null, selectedReport = null, selectedId = null, pairingData = null, toastTimer;
let previousEnrollment = null, refreshing = false;

async function api(path, options = {}) {
  const response = await fetch(path, { ...options, headers: {'Content-Type': 'application/json', ...options.headers} });
  if (!response.ok) {
    let data; try { data = await response.json(); } catch { /* use fallback */ }
    throw new Error(typeof data?.detail === 'string' ? data.detail : `Ошибка сервера (${response.status})`);
  }
  return response.json();
}
function toast(message) {
  $('toast').textContent = message; $('toast').hidden = false;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => $('toast').hidden = true, 4200);
}
function tab(name) {
  document.querySelectorAll('.tab-panel').forEach(panel => panel.hidden = panel.id !== `tab-${name}`);
  document.querySelectorAll('[data-tab]').forEach(button => button.classList.toggle('active', button.dataset.tab === name));
}
function timeLabel(value, date = false) {
  if (!value) return '—';
  return new Date(value).toLocaleString('ru-RU', date ? {day:'numeric',month:'short',hour:'2-digit',minute:'2-digit'} : {hour:'2-digit',minute:'2-digit'});
}
function initials(name) { return name.trim().split(/\s+/).slice(0,2).map(p => p[0]).join('').toUpperCase(); }
function renderReport() {
  const report = selectedReport;
  $('reportEmpty').hidden = !!report; $('reportContent').hidden = !report;
  $('openSession').hidden = !!dashboard?.active;
  $('reportTitle').textContent = report?.name || 'Текущая проверка';
  $('reportSubtitle').textContent = report ? `${report.group_name} · ${timeLabel(report.opened_at, true)}` : 'Проверка ещё не началась';
  $('reportBadge').textContent = !report ? 'Ожидание' : report.closed_at ? 'Завершена' : 'Идёт проверка';
  $('reportBadge').className = `badge ${report && !report.closed_at ? 'success' : 'neutral'}`;
  $('statTotal').textContent = report?.total ?? dashboard.people.length;
  $('statPresent').textContent = report?.present ?? 0;
  $('statWaiting').textContent = report ? report.total - report.present : '—';
  $('statGroup').textContent = report?.group_name || (dashboard.people.length ? 'Готовы к проверке' : 'Добавьте первую группу');
  $('waitingLabel').textContent = report?.closed_at ? 'Отсутствуют' : 'Ожидаем отметку';
  $('waitingHint').textContent = report ? report.closed_at ? 'Не отметились за проверку' : 'Проверка ещё продолжается' : 'После начала проверки';
  if (!report) return;
  $('closeSession').hidden = !!report.closed_at;
  $('exportLink').href = `/api/sessions/${report.id}/export.csv`;
  $('reportRows').innerHTML = report.people.map(person => {
    const present = !!person.present_at;
    const badge = present ? 'success' : report.closed_at ? 'absent' : 'neutral';
    const label = present ? 'Присутствует' : report.closed_at ? 'Отсутствует' : 'Ещё не отмечен';
    return `<tr><td><div class="person-cell"><span class="avatar">${escapeHTML(initials(person.name))}</span><span>${escapeHTML(person.name)}</span></div></td><td><span class="badge ${badge}">${label}</span></td><td class="muted">${timeLabel(person.present_at)}${person.method === 'manual' ? ' · вручную' : ''}</td><td class="align-right">${report.closed_at ? '—' : `<button class="mark-button ${present ? 'marked' : ''}" data-mark="${person.person_id}" data-present="${!present}" aria-label="${present ? 'Снять отметку' : 'Отметить'} ${escapeHTML(person.name)}">${present ? '✓ Отмечен' : 'Отметить'}</button>`}</td></tr>`;
  }).join('');
}
function renderPeople() {
  const search = $('peopleSearch').value.trim().toLocaleLowerCase('ru');
  const people = dashboard.people.filter(p => `${p.name} ${p.group_name}`.toLocaleLowerCase('ru').includes(search));
  $('peopleEmpty').hidden = people.length > 0;
  $('peopleRows').innerHTML = people.map(p => `<tr><td><div class="person-cell"><span class="avatar">${escapeHTML(initials(p.name))}</span>${escapeHTML(p.name)}</div></td><td>${escapeHTML(p.group_name)}</td><td><span class="badge ${p.samples >= 6 ? 'success' : 'absent'}">${p.samples} кадров</span></td><td class="align-right"><div class="people-actions"><button class="mark-button" data-recapture="${p.id}">Переснять</button><button class="mark-button" data-archive="${p.id}">В архив</button></div></td></tr>`).join('');
  $('navCount').textContent = dashboard.people.length;
  const groups = [...new Set(dashboard.people.map(p => p.group_name))];
  const options = groups.map(g => `<option value="${escapeHTML(g)}">${escapeHTML(g)}</option>`).join('');
  if ($('sessionGroup').innerHTML !== options) {
    const previous = $('sessionGroup').value;
    $('sessionGroup').innerHTML = options;
    if (groups.includes(previous)) $('sessionGroup').value = previous;
  }
  $('groups').innerHTML = options;
}
function renderHistory() {
  $('historyEmpty').hidden = dashboard.sessions.length > 0;
  $('historyRows').innerHTML = dashboard.sessions.map(s => `<button class="history-row" data-report="${s.id}"><div><h3>${escapeHTML(s.name)} ${s.closed_at ? '' : '<span class="badge success">Идёт</span>'}</h3><p>${escapeHTML(s.group_name)} · ${timeLabel(s.opened_at, true)}</p></div><span>${s.present} / ${s.total} на месте ↗</span></button>`).join('');
}
async function refresh() {
  if (refreshing) return;
  refreshing = true;
  try {
    dashboard = await api('/api/dashboard');
    $('notice').hidden = dashboard.models_ready;
    $('notice').textContent = 'Модели распознавания ещё не установлены. Запустите scripts/download_models.py на ноутбуке.';
    $('cameraDot').classList.toggle('online', dashboard.camera_online);
    $('cameraTitle').textContent = dashboard.camera_online ? 'Камера подключена' : 'Подключим Android';
    $('cameraDescription').textContent = dashboard.camera_online ? 'Получаем изображение с терминала. Можно регистрировать людей и проводить проверку.' : 'Телефон снимает лицо и показывает результат. Распознавание работает на ноутбуке.';
    if (!selectedId && dashboard.active) selectedId = dashboard.active.id;
    if (!selectedId && dashboard.sessions.length) selectedId = dashboard.sessions[0].id;
    selectedReport = selectedId ? await api(`/api/sessions/${selectedId}`) : null;
    renderReport(); renderPeople(); renderHistory();
    if (dashboard.enrollment) {
      previousEnrollment = dashboard.enrollment.id;
      $('enrollmentName').textContent = dashboard.enrollment.name;
      $('enrollmentProgress').value = dashboard.samples;
      $('enrollmentStatus').textContent = dashboard.enrollment_warning || (dashboard.camera_online ? `${dashboard.samples} из 6 кадров` : 'Включите камеру на подключённом телефоне');
      if (!$('enrollmentDialog').open) $('enrollmentDialog').showModal();
    } else {
      if ($('enrollmentDialog').open) $('enrollmentDialog').close();
      if (previousEnrollment) {
        if (dashboard.last_result?.status === 'enrolled' && dashboard.last_result.enrollment_id === previousEnrollment) toast(dashboard.last_result.message);
        previousEnrollment = null;
      }
    }
  } catch (error) {
    $('notice').hidden = false; $('notice').textContent = `Связь с сервером потеряна: ${error.message}`;
    $('cameraDot').classList.remove('online');
  } finally { refreshing = false; }
}
function openPerson() { $('personError').textContent = ''; $('personDialog').showModal(); }
document.querySelectorAll('[data-tab]').forEach(button => button.addEventListener('click', () => tab(button.dataset.tab)));
document.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click', () => $(button.dataset.close).close()));
$('addPerson').addEventListener('click', openPerson); $('emptyAdd').addEventListener('click', openPerson);
$('peopleSearch').addEventListener('input', renderPeople);
$('openSession').addEventListener('click', () => {
  if (!dashboard.people.length) { toast('Сначала добавьте хотя бы одного человека'); return; }
  $('sessionError').textContent = '';
  $('sessionForm').elements.name.value = `Проверка ${new Date().toLocaleDateString('ru-RU')}`;
  $('sessionDialog').showModal();
});
async function submitForm(event, endpoint, errorId, dialogId, onSuccess) {
  event.preventDefault(); const form = event.target, button = form.querySelector('[type=submit]'); button.disabled = true;
  $(errorId).textContent = '';
  try {
    const result = await api(endpoint, {method:'POST',body:JSON.stringify(Object.fromEntries(new FormData(form)))});
    $(dialogId).close(); if (onSuccess) onSuccess(result); await refresh();
  } catch (error) { $(errorId).textContent = error.message; } finally { button.disabled = false; }
}
$('personForm').addEventListener('submit', event => submitForm(event, '/api/enrollments', 'personError', 'personDialog'));
$('sessionForm').addEventListener('submit', event => submitForm(event, '/api/sessions', 'sessionError', 'sessionDialog', result => { selectedId = result.id; tab('check'); }));
async function cancelEnrollment() {
  try { await api('/api/enrollments', {method:'DELETE'}); previousEnrollment = null; $('enrollmentDialog').close(); await refresh(); }
  catch (error) { toast(error.message); }
}
$('cancelEnrollment').addEventListener('click', cancelEnrollment);
$('enrollmentDialog').addEventListener('cancel', event => { event.preventDefault(); cancelEnrollment(); });
$('closeSession').addEventListener('click', async () => {
  if (!selectedReport || !confirm('Завершить проверку? Неотметившиеся попадут в список отсутствующих.')) return;
  try { await api(`/api/sessions/${selectedReport.id}/close`, {method:'POST'}); await refresh(); toast('Проверка завершена. Результаты сохранены.'); }
  catch (error) { toast(error.message); }
});
$('reportRows').addEventListener('click', async event => {
  const button = event.target.closest('[data-mark]'); if (!button) return;
  button.disabled = true;
  try { await api(`/api/sessions/${selectedReport.id}/people/${button.dataset.mark}`, {method:'POST',body:JSON.stringify({present:button.dataset.present === 'true'})}); await refresh(); }
  catch (error) { toast(error.message); button.disabled = false; }
});
$('peopleRows').addEventListener('click', async event => {
  const recapture = event.target.closest('[data-recapture]');
  if (recapture) { await recapturePerson(recapture.dataset.recapture); return; }
  const button = event.target.closest('[data-archive]'); if (!button) return;
  if (!confirm('Убрать человека из будущих проверок? Сохранённые результаты останутся в истории.')) return;
  try { await api(`/api/people/${button.dataset.archive}/archive`, {method:'POST'}); await refresh(); }
  catch (error) { toast(error.message); }
});
async function recapturePerson(id) {
  try {
    await api(`/api/people/${id}/enrollment`, {method:'POST'});
    if ($('auditDialog').open) $('auditDialog').close();
    await refresh();
  } catch (error) { toast(error.message); }
}
$('auditDialog').addEventListener('click', event => {
  const button = event.target.closest('[data-recapture]');
  if (button) recapturePerson(button.dataset.recapture);
});
$('auditGallery').addEventListener('click', async () => {
  const button = $('auditGallery'); button.disabled = true;
  try {
    const audit = await api('/api/recognition/audit');
    const issues = [];
    const recapture = person => `<button class="mark-button" data-recapture="${escapeHTML(person.id)}">Переснять</button>`;
    audit.similar.forEach(pair => issues.push(`<li><div><strong>${escapeHTML(pair.first.name)} / ${escapeHTML(pair.second.name)}</strong><p>Похожие лица. Проверьте записи и попробуйте новую съёмку.</p></div><div class="people-actions">${recapture(pair.first)}${recapture(pair.second)}</div></li>`));
    audit.insufficient.forEach(person => issues.push(`<li><div><strong>${escapeHTML(person.name)}</strong><p>Недостаточно кадров: ${person.samples} из 6.</p></div>${recapture(person)}</li>`));
    audit.inconsistent.forEach(person => issues.push(`<li><div><strong>${escapeHTML(person.name)}</strong><p>Кадры заметно различаются. Нужно заново снять одного человека.</p></div>${recapture(person)}</li>`));
    audit.unusable.forEach(person => issues.push(`<li><div><strong>${escapeHTML(person.name)}</strong><p>Нет пригодных данных лица. Нужна повторная съёмка.</p></div>${recapture(person)}</li>`));
    $('auditSummary').textContent = !audit.people ? 'Сначала добавьте людей.' : issues.length ? `Есть записи, которые стоит проверить. Людей в базе: ${audit.people}.` : `Проверено ${audit.people} человек. Явных проблем в базе не найдено.`;
    $('auditIssues').innerHTML = issues.join('');
    $('auditDialog').showModal();
  } catch (error) { toast(error.message); } finally { button.disabled = false; }
});
$('historyRows').addEventListener('click', async event => {
  const button = event.target.closest('[data-report]'); if (!button) return;
  selectedId = button.dataset.report; tab('check'); await refresh();
});
async function openPairing() {
  try {
    pairingData = await api('/api/pairing');
    $('setupLink').href = pairingData.setup_url;
    $('laptopCamera').href = '/terminal' + new URL(pairingData.terminal_url).search;
    $('pairingDialog').showModal();
  } catch (error) { toast(error.message); }
}
$('openPairing').addEventListener('click', openPairing);
$('enrollmentPairing').addEventListener('click', openPairing);
$('copyPhoneLink').addEventListener('click', async () => {
  try { await navigator.clipboard.writeText(pairingData.terminal_url); toast('Ссылка скопирована'); }
  catch { toast('Копирование недоступно. Используйте QR-код.'); }
});
$('today').textContent = new Date().toLocaleDateString('ru-RU', {weekday:'long',day:'numeric',month:'long'});
refresh(); setInterval(refresh, 1500);
