'use strict';
const $ = id => document.getElementById(id);
const escapeHTML = text => String(text).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let dashboard = null, selectedReport = null, selectedId = null, pairingData = null, toastTimer;
let previousEnrollment = null, refreshing = false;
let renderedGroups = '', reportFilterSession = null;

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
function groupLabel(session) { return session.group_names?.join(' + ') || session.group_name; }
function setOptions(element, options) {
  if (element.innerHTML === options) return;
  const previous = element.value;
  element.innerHTML = options;
  if ([...element.options].some(option => option.value === previous)) element.value = previous;
}
function renderReport() {
  const report = selectedReport;
  $('reportEmpty').hidden = !!report; $('reportContent').hidden = !report;
  $('openSession').hidden = !!dashboard?.active;
  $('reportTitle').textContent = report?.name || 'Текущая проверка';
  $('reportSubtitle').textContent = report ? `${groupLabel(report)} · ${timeLabel(report.opened_at, true)}` : 'Проверка ещё не началась';
  $('reportBadge').textContent = !report ? 'Ожидание' : report.closed_at ? 'Завершена' : 'Идёт проверка';
  $('reportBadge').className = `badge ${report && !report.closed_at ? 'success' : 'neutral'}`;
  $('statTotal').textContent = report?.total ?? dashboard.people.length;
  $('statPresent').textContent = report?.present ?? 0;
  $('statWaiting').textContent = report ? report.total - report.present : '—';
  $('statGroup').textContent = report ? groupLabel(report) : dashboard.people.length ? 'Готовы к проверке' : 'Добавьте первую группу';
  $('waitingLabel').textContent = report?.closed_at ? 'Отсутствуют' : 'Ожидаем отметку';
  $('waitingHint').textContent = report ? report.closed_at ? 'Не отметились за проверку' : 'Проверка ещё продолжается' : 'После начала проверки';
  if (!report) return;
  $('closeSession').hidden = !!report.closed_at;
  setOptions($('reportGroupFilter'), '<option value="">Все группы</option>' + report.group_names.map(group => `<option value="${escapeHTML(group)}">${escapeHTML(group)}</option>`).join(''));
  if (reportFilterSession !== report.id) { $('reportGroupFilter').value = ''; reportFilterSession = report.id; }
  const selectedGroup = $('reportGroupFilter').value;
  $('reportGroupFilter').hidden = report.group_names.length < 2;
  $('groupSummary').innerHTML = report.groups.map(group => `<div class="group-summary-item"><strong>${escapeHTML(group.name)}</strong><span>${group.present} из ${group.total} на месте</span><small>${report.closed_at ? 'Отсутствуют' : 'Ожидаем'}: ${group.unmarked}</small></div>`).join('');
  $('exportLink').href = `/api/sessions/${report.id}/export.csv` + (selectedGroup ? `?group_name=${encodeURIComponent(selectedGroup)}` : '');
  $('exportLink').textContent = selectedGroup ? '↓ CSV выбранной группы' : '↓ Скачать CSV';
  const visiblePeople = report.people.filter(person => !selectedGroup || person.group_name === selectedGroup);
  $('reportRows').innerHTML = visiblePeople.map(person => {
    const present = !!person.present_at;
    const badge = present ? 'success' : report.closed_at ? 'absent' : 'neutral';
    const label = present ? 'Присутствует' : report.closed_at ? 'Отсутствует' : 'Ещё не отмечен';
    return `<tr><td><div class="person-cell"><span class="avatar">${escapeHTML(initials(person.name))}</span><span>${escapeHTML(person.name)}</span></div></td><td>${escapeHTML(person.group_name)}</td><td><span class="badge ${badge}">${label}</span></td><td class="muted">${timeLabel(person.present_at)}${person.method === 'manual' ? ' · вручную' : ''}</td><td class="align-right">${report.closed_at ? '—' : `<button class="mark-button ${present ? 'marked' : ''}" data-mark="${person.person_id}" data-present="${!present}" aria-label="${present ? 'Снять отметку' : 'Отметить'} ${escapeHTML(person.name)}">${present ? '✓ Отмечен' : 'Отметить'}</button>`}</td></tr>`;
  }).join('');
}
function renderPeople() {
  const search = $('peopleSearch').value.trim().toLocaleLowerCase('ru');
  const selectedGroup = $('peopleGroupFilter').value;
  const people = dashboard.people.filter(p => (!selectedGroup || p.group_name === selectedGroup) && `${p.name} ${p.group_name}`.toLocaleLowerCase('ru').includes(search));
  $('peopleEmpty').hidden = people.length > 0;
  $('peopleRows').innerHTML = people.map(p => `<tr><td><div class="person-cell"><span class="avatar">${escapeHTML(initials(p.name))}</span>${escapeHTML(p.name)}</div></td><td>${escapeHTML(p.group_name)}</td><td><span class="badge ${p.samples >= 6 ? 'success' : 'absent'}">${p.samples ? `${p.samples} кадров` : 'Нужно переснять'}</span></td><td class="align-right"><div class="people-actions"><button class="mark-button" data-move="${p.id}">Группа</button><button class="mark-button" data-recapture="${p.id}">Переснять</button><button class="mark-button" data-archive="${p.id}">В архив</button></div></td></tr>`).join('');
  $('navCount').textContent = dashboard.people.length;
}
function renderGroups() {
  const groups = dashboard.groups;
  const options = groups.map(group => `<option value="${escapeHTML(group.name)}">${escapeHTML(group.name)}</option>`).join('');
  $('groups').innerHTML = options;
  setOptions($('peopleGroupFilter'), '<option value="">Все группы</option>' + options);
  setOptions($('moveGroup'), options);
  $('groupCards').innerHTML = groups.map(group => `<section class="card group-card"><span class="group-card-icon">♧</span><h2>${escapeHTML(group.name)}</h2><p>${group.people} человек</p><button class="button secondary full" data-view-group="${escapeHTML(group.name)}">Открыть список →</button></section>`).join('');
  const signature = JSON.stringify(groups);
  if (signature !== renderedGroups) {
    const checked = new Set([...$('sessionGroups').querySelectorAll('input:checked')].map(input => input.value));
    $('sessionGroups').innerHTML = groups.map(group => `<label class="group-option ${group.people ? '' : 'disabled'}"><input type="checkbox" name="group_names" value="${escapeHTML(group.name)}" ${checked.has(group.name) && group.people ? 'checked' : ''} ${group.people ? '' : 'disabled'}><span>${escapeHTML(group.name)}</span><small>${group.people} человек</small></label>`).join('');
    renderedGroups = signature;
  }
}
function renderHistory() {
  $('historyEmpty').hidden = dashboard.sessions.length > 0;
  $('historyRows').innerHTML = dashboard.sessions.map(s => `<button class="history-row" data-report="${s.id}"><div><h3>${escapeHTML(s.name)} ${s.closed_at ? '' : '<span class="badge success">Идёт</span>'}</h3><p>${escapeHTML(groupLabel(s))} · ${timeLabel(s.opened_at, true)}</p></div><span>${s.present} / ${s.total} на месте ↗</span></button>`).join('');
}
async function refresh() {
  if (refreshing) return;
  refreshing = true;
  try {
    dashboard = await api('/api/dashboard');
    $('modelLabel').textContent = `${dashboard.model_name} · проверка живости отключена`;
    $('notice').hidden = dashboard.models_ready && !dashboard.needs_recapture;
    $('notice').textContent = !dashboard.models_ready ? 'Модели распознавания ещё не установлены. Запустите scripts/download_models.py на ноутбуке.' : `${dashboard.model_name}: нужно обновить лица у ${dashboard.needs_recapture} человек. Откройте «Люди» и нажмите «Переснять». Имена, группы и история сохраняются.`;
    $('cameraDot').classList.toggle('online', dashboard.camera_online);
    $('cameraTitle').textContent = dashboard.camera_online ? 'Камера подключена' : 'Подключим Android';
    $('cameraDescription').textContent = dashboard.camera_online ? 'Получаем изображение с терминала. Можно регистрировать людей и проводить проверку.' : 'Телефон снимает лицо и показывает результат. Распознавание работает на ноутбуке.';
    if (!selectedId && dashboard.active) selectedId = dashboard.active.id;
    if (!selectedId && dashboard.sessions.length) selectedId = dashboard.sessions[0].id;
    selectedReport = selectedId ? await api(`/api/sessions/${selectedId}`) : null;
    renderGroups(); renderReport(); renderPeople(); renderHistory();
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
function openPerson() {
  $('personError').textContent = '';
  const chosen = $('peopleGroupFilter').value;
  if (chosen) $('personForm').elements.group_name.value = chosen;
  $('personDialog').showModal();
}
document.querySelectorAll('[data-tab]').forEach(button => button.addEventListener('click', () => tab(button.dataset.tab)));
document.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click', () => $(button.dataset.close).close()));
$('addPerson').addEventListener('click', openPerson); $('emptyAdd').addEventListener('click', openPerson);
$('peopleSearch').addEventListener('input', renderPeople);
$('peopleGroupFilter').addEventListener('change', renderPeople);
$('reportGroupFilter').addEventListener('change', renderReport);
$('addGroup').addEventListener('click', () => {
  $('groupError').textContent = ''; $('groupForm').reset(); $('groupDialog').showModal();
});
$('groupCards').addEventListener('click', event => {
  const button = event.target.closest('[data-view-group]'); if (!button) return;
  $('peopleGroupFilter').value = button.dataset.viewGroup; renderPeople(); tab('people');
});
$('openSession').addEventListener('click', () => {
  if (!dashboard.people.length) { toast('Сначала добавьте хотя бы одного человека'); return; }
  $('sessionError').textContent = '';
  $('sessionForm').elements.name.value = `Проверка ${new Date().toLocaleDateString('ru-RU')}`;
  const chosen = $('peopleGroupFilter').value;
  const eligible = [...$('sessionGroups').querySelectorAll('input:not(:disabled)')];
  eligible.forEach(input => input.checked = chosen ? input.value === chosen : eligible.length === 1);
  $('sessionDialog').showModal();
});
async function submitForm(event, endpoint, errorId, dialogId, onSuccess, serialize) {
  event.preventDefault(); const form = event.target, button = form.querySelector('[type=submit]'); button.disabled = true;
  $(errorId).textContent = '';
  try {
    const data = new FormData(form);
    const result = await api(endpoint, {method:'POST',body:JSON.stringify(serialize ? serialize(data) : Object.fromEntries(data))});
    $(dialogId).close(); if (onSuccess) onSuccess(result); await refresh();
  } catch (error) { $(errorId).textContent = error.message; } finally { button.disabled = false; }
}
$('personForm').addEventListener('submit', event => submitForm(event, '/api/enrollments', 'personError', 'personDialog'));
$('sessionForm').addEventListener('submit', event => {
  if (!$('sessionGroups').querySelector('input:checked')) { event.preventDefault(); $('sessionError').textContent = 'Выберите хотя бы одну группу'; return; }
  return submitForm(event, '/api/sessions', 'sessionError', 'sessionDialog', result => { selectedId = result.id; tab('check'); }, data => ({name: data.get('name'), group_names: data.getAll('group_names')}));
});
$('groupForm').addEventListener('submit', event => submitForm(event, '/api/groups', 'groupError', 'groupDialog', () => toast('Группа создана')));
$('moveForm').addEventListener('submit', event => submitForm(event, `/api/people/${$('movePersonId').value}/group`, 'moveError', 'moveDialog', () => toast('Группа изменена. Сохранённые проверки остаются прежними.'), data => ({group_name: data.get('group_name')})));
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
  const move = event.target.closest('[data-move]');
  if (move) {
    const person = dashboard.people.find(item => item.id === move.dataset.move);
    $('movePersonId').value = person.id; $('movePersonName').textContent = person.name;
    $('moveGroup').value = person.group_name; $('moveError').textContent = ''; $('moveDialog').showModal(); return;
  }
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
