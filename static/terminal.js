'use strict';
const $ = id => document.getElementById(id);
let stream = null, running = false, facing = 'user', timer = null, state = null, holdUntil = 0;
let generation = 0, stateBusy = false;
const video = $('video'), canvas = $('capture');
const FRAME_INTERVAL_MS = 200;
function message(text, kind = '', progress = null, hold = false) {
  if (performance.now() < holdUntil && !hold) return;
  if (hold) holdUntil = performance.now() + 1600;
  $('resultMessage').textContent = text;
  $('result').className = `terminal-result ${kind}`;
  $('progress').hidden = progress === null;
  if (progress !== null) $('progress').value = progress;
}
async function readJSON(response) {
  let data; try { data = await response.json(); } catch { throw new Error('Сервер недоступен'); }
  if (!response.ok) throw new Error(typeof data.detail === 'string' ? data.detail : 'Ошибка сервера');
  return data;
}
async function pollState() {
  if (stateBusy) return; stateBusy = true;
  try {
    state = await readJSON(await fetch('/api/terminal'));
    $('networkState').textContent = 'На связи'; $('networkState').className = 'badge success';
    $('sessionTitle').textContent = state.enrollment ? state.enrollment.name : state.active ? state.active.name : 'Камера готова';
    $('modeLabel').textContent = state.enrollment ? 'РЕГИСТРАЦИЯ ЛИЦА' : state.active ? 'ИДЁТ ПЕРЕКЛИЧКА' : 'ТЕРМИНАЛ ПОСЕЩАЕМОСТИ';
    $('sessionDescription').textContent = state.enrollment ? 'Посмотрите прямо в камеру. Сохраним 6 качественных кадров.' : state.active ? `${state.active.group_names?.join(' + ') || state.active.group_name} · Подходите по одному` : 'Начните проверку или регистрацию на ноутбуке.';
    $('startCamera').disabled = !state.models_ready;
    if (!state.models_ready) message('Модели распознавания ещё не установлены на ноутбуке', 'error');
  } catch (error) {
    $('networkState').textContent = 'Нет связи'; $('networkState').className = 'badge neutral';
    message(error.message, 'error', null, true);
  } finally { stateBusy = false; }
}
async function startCamera() {
  $('startCamera').disabled = true;
  try {
    if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) throw new Error('Для камеры откройте HTTPS-ссылку из панели и установите сертификат по инструкции.');
    stream = await navigator.mediaDevices.getUserMedia({audio:false,video:{facingMode:{ideal:facing},width:{ideal:640},height:{ideal:480}}});
    video.srcObject = stream; await video.play();
    running = true; generation++;
    $('cameraPlaceholder').hidden = true; $('viewfinder').hidden = false;
    $('cameraStage').classList.add('running');
    $('startCamera').hidden = true; $('cameraControls').hidden = false;
    $('cameraStage').classList.toggle('back-camera', facing === 'environment');
    holdUntil = 0; message('Камера включена. Посмотрите прямо');
    const track = stream.getVideoTracks()[0];
    track.addEventListener('ended', () => { if (running) { stopCamera(); message('Камера отключена. Включите её снова.', 'error', null, true); } });
    capture(generation);
  } catch (error) {
    if (stream) stream.getTracks().forEach(track => track.stop());
    stream = null;
    const known = {NotAllowedError:'Разрешите доступ к камере в настройках браузера.',NotFoundError:'Камера не найдена.',NotReadableError:'Камера занята другим приложением.'};
    message(known[error.name] || error.message, 'error', null, true);
  } finally { $('startCamera').disabled = !state?.models_ready; }
}
function stopCamera() {
  running = false; generation++; clearTimeout(timer);
  if (stream) stream.getTracks().forEach(track => track.stop());
  stream = null; video.srcObject = null;
  $('cameraStage').classList.remove('running');
  $('cameraPlaceholder').hidden = false; $('faceBox').hidden = true; $('viewfinder').hidden = true;
  $('startCamera').hidden = false; $('cameraControls').hidden = true;
  holdUntil = 0; message('Камера выключена');
}
async function capture(version) {
  if (!running || version !== generation) return;
  const cycleStarted = performance.now();
  let delay = null;
  try {
    if (!video.videoWidth || video.readyState < 2) throw new Error('Ожидаем изображение камеры…');
    const scale = Math.min(1, 960 / Math.max(video.videoWidth, video.videoHeight));
    canvas.width = Math.round(video.videoWidth * scale); canvas.height = Math.round(video.videoHeight * scale);
    canvas.getContext('2d').drawImage(video, 0, 0, canvas.width, canvas.height);
    const blob = await new Promise(resolve => canvas.toBlob(resolve, 'image/jpeg', .85));
    if (!blob) throw new Error('Не удалось получить кадр камеры');
    const response = await fetch('/api/frames', {method:'POST',headers:{'Content-Type':'image/jpeg'},body:blob,signal:AbortSignal.timeout(8000)});
    const result = await readJSON(response);
    if (!running || version !== generation) return;
    $('networkState').textContent = 'На связи'; $('networkState').className = 'badge success';
    if (result.box) {
      const [x,y,w,h] = result.box; const box = $('faceBox');
      box.style.left = `${100*x}%`; box.style.top = `${100*y}%`; box.style.width = `${100*w}%`; box.style.height = `${100*h}%`; box.hidden = false;
      $('viewfinder').hidden = true;
    } else { $('faceBox').hidden = true; $('viewfinder').hidden = false; }
    const success = ['present','already','enrolled'].includes(result.status);
    message(result.message, success ? 'success' : ['unknown','other_group','enrollment_conflict'].includes(result.status) ? 'error' : '', result.progress ?? null, success);
    if (result.status === 'enrolled') { delay = 1800; pollState(); }
  } catch (error) {
    if (version !== generation) return;
    $('faceBox').hidden = true; $('networkState').textContent = 'Проверяем связь'; $('networkState').className = 'badge neutral';
    holdUntil = 0; message(error.name === 'TimeoutError' ? 'Сервер не отвечает. Проверьте связь с ноутбуком.' : error.message, 'error', null, true);
    delay = 1500;
  }
  if (running && version === generation) {
    // Keep one request in flight. Network and processing time count toward the
    // capture interval; errors and completed enrollment retain their cooldown.
    const nextDelay = delay ?? Math.max(0, FRAME_INTERVAL_MS - (performance.now() - cycleStarted));
    timer = setTimeout(() => capture(version), nextDelay);
  }
}
$('startCamera').addEventListener('click', startCamera);
$('stopCamera').addEventListener('click', stopCamera);
$('switchCamera').addEventListener('click', async () => { stopCamera(); facing = facing === 'user' ? 'environment' : 'user'; await startCamera(); });
window.addEventListener('pagehide', stopCamera);
document.addEventListener('visibilitychange', () => { if (document.hidden && running) { stopCamera(); message('Камера приостановлена. Нажмите «Включить камеру», чтобы продолжить.'); } });
pollState(); setInterval(pollState, 2000);
