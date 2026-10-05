import * as assert from 'node:assert/strict';
import * as vscode from 'vscode';
import { revisionUri, RevisionContentProvider, SCHEME } from '../review/diff';
import { navigatorFraction, navigatorSplit, parseReviewMessage, renderReviewHtml, reviewData, ReviewWindow } from '../review/reviewWindow';
import { ReviewSession } from '../review/session';
import { FakeProvider, memento } from './fakes';
import { makeRepo, type TestRepo } from './repo';

suite('review window', () => {
  let repo: TestRepo;
  let provider: FakeProvider;
  let session: ReviewSession;

  setup(async () => {
    repo = await makeRepo();
    provider = new FakeProvider(repo);
    provider.threads = [
      { id: 'T1', path: 'src/app.ts', side: 'head', line: 5, isOutdated: false, isResolved: false, comments: [{ id: 'C1', author: 'ana', body: 'why?', createdAt: '' }] },
      { id: 'T2', path: 'src/app.ts', side: 'head', line: 2, isOutdated: false, isResolved: true, comments: [{ id: 'C2', author: 'luis', body: 'done', createdAt: '' }] },
    ];
    session = new ReviewSession(provider, repo.root, 'origin', memento(), 'github:octo/app');
    await session.open(1);
  });

  teardown(async () => {
    session.close();
    session.dispose();
    await vscode.commands.executeCommand('workbench.action.closeAllEditors');
  });

  test('sends the page the files in their folders and the comments apart', () => {
    const data = reviewData(session.require());
    assert.deepStrictEqual([data.number, data.title, data.author], [1, 'Test PR', 'ana']);
    assert.deepStrictEqual(data.files.map(f => ('folder' in f ? f.folder : f.name)), ['docs', 'src', 'keep.txt']);
    assert.deepStrictEqual(data.active, [{ id: 'T1', place: 'src/app.ts:5', author: 'ana', text: 'why?', outdated: false }]);
    assert.deepStrictEqual(data.resolved.map(t => t.place), ['src/app.ts:2']);
    assert.deepStrictEqual([data.viewed, data.total, data.labels.progress], [0, 4, '0 of 4 files viewed']);
    session.require().viewed = new Set(['src/app.ts', 'src/new file.ts', 'docs/new name.md', 'keep.txt']);
    assert.strictEqual(reviewData(session.require()).labels.progress, 'All 4 files viewed');
    assert.strictEqual(data.aiReview, undefined);
  });

  test('lists the drafts, and counts in each file what is left to look at', async () => {
    await session.addDraft('src/app.ts', 6, 7, 'Mine\n\nmore', 'manual');
    await session.addDraft('src/app.ts', 5, 5, 'Null check missing', { profile: 'Security', engine: 'claude', severity: 'error' });
    const data = reviewData(session.require());
    assert.deepStrictEqual(
      data.drafts.map(d => [d.place, d.who, d.text, d.kind]),
      [
        ['src/app.ts:5', 'Security', 'Null check missing', 'error'],
        ['src/app.ts:6-7', '', 'Mine', 'manual'],
      ],
    );
    const src = data.files.find(f => 'folder' in f && f.folder === 'src');
    const app = src && 'children' in src ? src.children.find(f => 'file' in f && f.file === 'src/app.ts') : undefined;
    // Counts remain distinct: two drafts and one active conversation, never the resolved one.
    assert.strictEqual(app && 'comments' in app ? app.comments : undefined, 1);
    assert.strictEqual(app && 'drafts' in app ? app.drafts : undefined, 2);
    assert.strictEqual(data.description, 'Body');
    const c = session.require();
    const [draft] = c.drafts.all();
    assert.deepStrictEqual(parseReviewMessage({ type: 'openDraft', id: draft.id }, c), { type: 'openDraft', id: draft.id });
    assert.strictEqual(parseReviewMessage({ type: 'openDraft', id: 'nope' }, c), undefined);
    assert.deepStrictEqual(parseReviewMessage({ type: 'cancelAiReview' }, c), { type: 'cancelAiReview' });
  });

  test('accepts only files and threads of the pull request and the page buttons', () => {
    const c = session.require();
    assert.deepStrictEqual(parseReviewMessage({ type: 'openFile', path: 'src/app.ts' }, c), { type: 'openFile', path: 'src/app.ts' });
    assert.strictEqual(parseReviewMessage({ type: 'openFile', path: '../../etc/passwd' }, c), undefined);
    assert.deepStrictEqual(parseReviewMessage({ type: 'viewed', path: 'src/app.ts', viewed: true }, c), { type: 'viewed', path: 'src/app.ts', viewed: true });
    assert.strictEqual(parseReviewMessage({ type: 'viewed', path: 'src/app.ts', viewed: 'yes' }, c), undefined);
    assert.deepStrictEqual(parseReviewMessage({ type: 'openThread', id: 'T1' }, c), { type: 'openThread', id: 'T1' });
    assert.strictEqual(parseReviewMessage({ type: 'openThread', id: 'T9' }, c), undefined);
    assert.deepStrictEqual(parseReviewMessage({ type: 'command', command: 'publish' }, c), { type: 'command', command: 'publish' });
    // Only the page's own buttons: no running any command a page could name.
    assert.strictEqual(parseReviewMessage({ type: 'command', command: 'workbench.action.terminal.new' }, c), undefined);
    assert.strictEqual(parseReviewMessage({ type: 'command', command: 'toString' }, c), undefined);
  });


  test('bounds the navigator width and restores the saved user width', () => {
    assert.strictEqual(navigatorFraction(1400), 280 / 1400);
    assert.strictEqual(navigatorFraction(1400, 420), 0.3);
    assert.strictEqual(navigatorFraction(1400, 100), 260 / 1400);
    assert.strictEqual(navigatorFraction(600), 0.4);
    // A wide window keeps the page narrow instead of giving it a share of the width.
    assert.strictEqual(navigatorFraction(4000), 280 / 4000);
    assert.strictEqual(navigatorFraction(0), 0.3);
    assert.strictEqual(navigatorFraction(1400, NaN), 280 / 1400);
    assert.deepStrictEqual(parseReviewMessage({ type: 'resized' }, session.require()), { type: 'resized' });
  });

  test('keeps the page at its width in pixels; only dragging the sash changes it', () => {
    // Opening: the default width, or the remembered one.
    assert.deepStrictEqual(navigatorSplit(300, 1000, undefined, undefined), { split: 280 / 1000 });
    assert.deepStrictEqual(navigatorSplit(300, 1000, undefined, 350), { split: 350 / 1000 });
    // The window grew to 2000 pixels and kept the split: the page went to 560 and goes back to 280.
    assert.deepStrictEqual(navigatorSplit(560, 2000, 280 / 1000, undefined), { split: 280 / 2000 });
    // Rounding is not a drag.
    assert.deepStrictEqual(navigatorSplit(281, 2000, 280 / 2000, undefined), { split: 280 / 2000 });
    // The window shrank back to 1024 and VS Code held the page at a group's minimum (220), moving the split:
    // not a drag, the page goes back to 280.
    assert.deepStrictEqual(navigatorSplit(220, 1024, 280 / 2000, undefined), { split: 280 / 1024 });
    // The split moved: the user dragged the sash, and that width is remembered.
    assert.deepStrictEqual(navigatorSplit(400, 2000, 280 / 2000, undefined), { split: 400 / 2000, remember: 400 });
  });

  test('the live webview filters tabs, preserves state, and treats PR content as text', async () => {
    await session.addDraft('src/app.ts', 5, 5, 'Null check missing', { profile: 'Security', engine: 'claude', severity: 'error' });
    await session.addDraft('src/app.ts', 6, 6, 'Manual note');
    const data = reviewData(session.require());
    data.description = '<button id="publishLabel">untrusted</button><script>window.evil=true</script>';
    const panel = vscode.window.createWebviewPanel('smartPrReview.test', 'Review UI test', vscode.ViewColumn.Active, { enableScripts: true });
    const checks = `
      let updates = 0;
      window.addEventListener('message', ({data: message}) => {
        if (message.type !== 'update') return;
        try {
          const check = (value, why) => { if (!value) throw new Error(why); };
          const get = id => document.getElementById(id);
          const change = (id, value) => { get(id).value = value; get(id).dispatchEvent(new Event('change')); };
          updates++;
          if (updates === 2) {
            check(get('search').value === 'no-match' && get('fileFilter').value === 'pending', 'filters survive data updates');
            check(get('threadFilter').value === 'resolved' && get('profileFilter').value === 'manual', 'other tabs retain their filters');
            vscode.postMessage({type:'testNextPr'});
            return;
          }
          if (updates === 3) {
            check(get('search').value === '' && get('fileFilter').value === 'all', 'new PR clears old filters');
            check(get('tab-files').getAttribute('aria-selected') === 'true', 'new PR starts on files');
            vscode.postMessage({type:'testResult', ok:true});
            return;
          }
          check(get('description').textContent.includes('<button'), 'description is literal text');
          check(!window.evil && document.querySelectorAll('#publishLabel').length === 1, 'untrusted HTML cannot create controls');
          check(!get('files').hidden && get('drafts').hidden, 'files selected initially');
          get('tab-drafts').click();
          check(!get('drafts').hidden && get('files').hidden, 'draft tab selected');
          check(get('draftsList').children.length === 2, 'both drafts visible');
          change('severityFilter', 'error');
          check(get('draftsList').children.length === 1 && get('draftsList').textContent.includes('Error'), 'severity filter and written label');
          change('profileFilter', 'manual');
          check(!get('empty').hidden, 'empty filtered state');
          change('severityFilter', 'all');
          check(get('draftsList').children.length === 1 && get('draftsList').textContent.includes('Manual note'), 'manual origin filter');
          get('tab-conversations').click();
          check(get('threadsList').children.length === 1 && get('threadsList').textContent.includes('why?'), 'active by default');
          change('threadFilter', 'resolved');
          check(get('threadsList').children.length === 1 && get('threadsList').textContent.includes('done'), 'resolved available');
          get('tab-conversations').dispatchEvent(new KeyboardEvent('keydown', {key: 'Home', bubbles: true}));
          check(get('tab-files').getAttribute('aria-selected') === 'true', 'keyboard tab navigation');
          change('fileFilter', 'pending');
          check(document.querySelectorAll('#fileList .file').length === 1, 'pending file filter');
          check(document.querySelectorAll('#fileList .comments').length === 2, 'separate counters');
          get('search').value = 'no-match'; get('search').dispatchEvent(new Event('input'));
          check(!get('empty').hidden, 'search empty state');
          vscode.postMessage({type:'testRefresh'});
        } catch(e) { vscode.postMessage({type:'testResult', error: String(e)}); }
      });
    `;
    try {
      const result = new Promise<void>((resolve, reject) => {
        panel.webview.onDidReceiveMessage(message => {
          if (message.type === 'ready') {
            void panel.webview.postMessage({ type: 'update', data });
          }
          if (message.type === 'testRefresh') { void panel.webview.postMessage({ type: 'update', data }); }
          if (message.type === 'testNextPr') { void panel.webview.postMessage({ type: 'update', data: { ...data, number: 2 } }); }
          if (message.type === 'testResult') {
            if (message.ok) {resolve();} else {reject(new Error(message.error));}
          }
        });
      });
      panel.webview.html = renderReviewHtml('test-nonce').replace('</body>', `<script nonce="test-nonce">${checks}</script></body>`);
      await result;
    } finally { panel.dispose(); }
  });

  test('opens in a window of its own, and opens diffs beside it there', async () => {
    const registration = vscode.workspace.registerTextDocumentContentProvider(SCHEME, new RevisionContentProvider(repo.root));
    try {
      await ReviewWindow.show(session);
      // The window opens narrow on the page and wide on the diffs, with the diff group ready.
      const groupsAtOpen = vscode.window.tabGroups.all.length;
      const layout = await vscode.commands.executeCommand<{ groups: { size: number }[] }>('vscode.getEditorLayout');
      assert.strictEqual(layout.groups.length, 2);
      assert.ok(layout.groups[0].size < layout.groups[1].size, JSON.stringify(layout));
      const page = () => vscode.window.tabGroups.all.find(g => g.tabs.some(t => t.input instanceof vscode.TabInputWebview && t.input.viewType.endsWith('smartPrReview.review')));
      assert.ok(page(), 'the review page is open');
      const window = (ReviewWindow as unknown as { instance: { onMessage(raw: unknown): Promise<void> } }).instance;
      await window.onMessage({ type: 'openFile', path: 'src/app.ts' });
      const diff = vscode.window.tabGroups.all.find(g =>
        g.tabs.some(t => t.input instanceof vscode.TabInputTextDiff && t.input.modified.toString() === revisionUri('src/app.ts', repo.headSha).toString()),
      );
      assert.ok(diff, 'the diff is open');
      assert.notStrictEqual(diff, page(), 'the diff is beside the page, not over it');
      assert.strictEqual(vscode.window.tabGroups.all.length, groupsAtOpen, 'the first diff goes to the group made for it');
      // The next files open in that same group, not in a new one each time.
      const groups = vscode.window.tabGroups.all.length;
      await window.onMessage({ type: 'openFile', path: 'src/new file.ts' });
      const second = vscode.window.tabGroups.all.find(g =>
        g.tabs.some(t => t.input instanceof vscode.TabInputTextDiff && t.input.modified.toString() === revisionUri('src/new file.ts', repo.headSha).toString()),
      );
      assert.strictEqual(second?.viewColumn, diff.viewColumn);
      await window.onMessage({ type: 'openThread', id: 'T1' });
      assert.strictEqual(vscode.window.tabGroups.all.length, groups);
      await window.onMessage({ type: 'viewed', path: 'src/app.ts', viewed: true });
      assert.ok(session.require().viewed.has('src/app.ts'));
      await window.onMessage({ type: 'command', command: 'publish' });
      assert.strictEqual(vscode.window.tabGroups.all.length, groups, 'preparing publication does not add a third column');
      // Exiting the review closes the page.
      session.close();
      assert.strictEqual((ReviewWindow as unknown as { instance?: unknown }).instance, undefined);
    } finally {
      registration.dispose();
    }
  });
});
