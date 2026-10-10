import * as assert from 'node:assert/strict';
import * as vscode from 'vscode';
import { allowedVotes, parseMessage, PublishPanel, publishRevision, renderPublishHtml } from '../review/publishPanel';
import { ReviewSession } from '../review/session';
import { FakeProvider, memento } from './fakes';
import { makeRepo } from './repo';

type TestPanel = { shown?: number; panel: vscode.WebviewPanel; onMessage(raw: unknown): Promise<void> };
const instance = () => (PublishPanel as unknown as { instance?: TestPanel }).instance;

async function showPanel(session: ReviewSession): Promise<TestPanel> {
  PublishPanel.show(session);
  // Markdown rendering activates another extension: wait for the preview, not a fixed delay.
  for (let i = 0; i < 200; i++) {
    const panel = instance();
    if (panel?.shown === session.require().pr.id) {return panel;}
    await new Promise(resolve => setTimeout(resolve, 25));
  }
  assert.fail('Publish preview did not render');
}

suite('publish panel', () => {
  teardown(() => instance()?.panel.dispose());

  test('renders a strict CSP and escapes pull request text', () => {
    const html = renderPublishHtml({
      cspSource: 'vscode-webview:',
      nonce: 'abc',
      title: '#1 <script>alert(1)</script>',
      url: 'https://example.com/pr/1',
      author: 'ana',
      descriptionHtml: '<p>ok</p>',
      summary: '"quoted"',
      draftCount: 2,
      votes: ['comment', 'approve'],
      vote: 'approve',
    });
    assert.match(html, /script-src 'nonce-abc'/);
    // The description is raw HTML from the PR: a <form> or <base> in it must not reach anywhere.
    assert.match(html, /form-action 'none'/);
    assert.match(html, /base-uri 'none'/);
    assert.match(html, /<button id="publish">Publish now<\/button>/);
    assert.ok(!html.includes('id="reviewWithAI"'));
    assert.ok(!html.includes('<script>alert(1)</script>'));
    assert.ok(html.includes('&lt;script&gt;alert(1)&lt;/script&gt;'));
    assert.ok(html.includes('&quot;quoted&quot;'));
    assert.deepStrictEqual([...html.matchAll(/<option value="(\w+)"/g)].map(m => m[1]), ['comment', 'approve']);
    // The chosen vote survives re-renders, so a retry after a failed vote does not silently fall back to "Comment".
    assert.deepStrictEqual([...html.matchAll(/<option value="(\w+)" selected>/g)].map(m => m[1]), ['approve']);
  });

  test('puts the description after the controls, so its HTML cannot take their place', () => {
    const html = renderPublishHtml({
      cspSource: 'vscode-webview:',
      nonce: 'abc',
      title: '#1',
      url: 'https://example.com/pr/1',
      author: 'ana',
      // What markdown.api.render returns for raw HTML in a description.
      descriptionHtml: '<textarea id="summary" hidden>Approved by the author</textarea><button id="publish">Publish</button>',
      summary: '',
      draftCount: 0,
      votes: ['comment'],
      vote: 'comment',
    });
    const description = html.indexOf('<section class="description">');
    assert.ok(html.indexOf('</script>') < description);
    assert.ok(html.indexOf('id="summary"') < description && html.indexOf('id="publish"') < description);
  });

  test('accepts only well-formed messages with a supported vote', () => {
    const votes = ['comment', 'approve'] as const;
    assert.deepStrictEqual(parseMessage({ type: 'publish', text: 'hi', vote: 'approve', revision: 'version' }, votes), { type: 'publish', text: 'hi', vote: 'approve', revision: 'version' });
    assert.deepStrictEqual(parseMessage({ type: 'summary', text: 'draft' }, votes), { type: 'summary', text: 'draft' });
    assert.strictEqual(parseMessage({ type: 'publish', text: 'hi', vote: 'reject' }, votes), undefined);
    assert.strictEqual(parseMessage({ type: 'publish', text: 42, vote: 'approve' }, votes), undefined);
    assert.strictEqual(parseMessage('publish', votes), undefined);
    assert.deepStrictEqual(parseMessage({ type: 'vote', vote: 'approve' }, votes), { type: 'vote', vote: 'approve' });
    assert.strictEqual(parseMessage({ type: 'vote', vote: 'reject' }, votes), undefined);
    assert.deepStrictEqual(parseMessage({ type: 'reviewWithAI' }, votes), { type: 'reviewWithAI' });
  });

  test('a successful publish closes the panel without a render failing on the closed webview', async () => {
    const repo = await makeRepo();
    const session = new ReviewSession(new FakeProvider(repo), repo.root, 'origin', memento(), 'github:octo/app');
    await session.open(1);
    await session.addDraft('src/app.ts', 5, 5, 'Nit');
    const rejections: unknown[] = [];
    const onRejection = (reason: unknown) => rejections.push(reason);
    process.on('unhandledRejection', onRejection);
    try {
      const panel = await showPanel(session);
      // What the webview posts when the reviewer presses "Publish Review".
      await panel.onMessage({ type: 'publish', text: 'LGTM', vote: 'comment', revision: publishRevision(session.require()) });
      assert.deepStrictEqual(session.require().drafts.all(), []);
      assert.strictEqual(instance(), undefined);
      await new Promise(resolve => setImmediate(resolve));
    } finally {
      process.off('unhandledRejection', onRejection);
      session.dispose();
    }
    assert.deepStrictEqual(rejections.map(String), []);
  });

  test('with approval turned off, the approving votes are not offered (nor accepted from the webview)', () => {
    const ado = ['comment', 'approve', 'approveWithSuggestions', 'waitForAuthor', 'reject'] as const;
    assert.deepStrictEqual(allowedVotes(ado, false), ['comment', 'waitForAuthor', 'reject']);
    assert.deepStrictEqual(allowedVotes(ado, true), ado);
    assert.strictEqual(parseMessage({ type: 'publish', text: 'ok', vote: 'approve' }, allowedVotes(ado, false)), undefined);
  });

  test('a page still showing one pull request cannot publish to another opened since', async () => {
    const repo = await makeRepo();
    const provider = new FakeProvider(repo);
    const session = new ReviewSession(provider, repo.root, 'origin', memento(), 'github:octo/app');
    await session.open(1);
    await session.addDraft('src/app.ts', 5, 5, 'Nit');
    const panel = await showPanel(session);
    try {
      // Another pull request is now current, and the page has not rendered it yet.
      const c = session.require();
      c.pr = { ...c.pr, id: 2 };
      await panel.onMessage({ type: 'publish', text: 'LGTM', vote: 'comment', revision: publishRevision(session.require()) });
      assert.deepStrictEqual(provider.published, []);
      assert.strictEqual(session.require().drafts.all().length, 1);
    } finally {
      session.close();
      session.dispose();
    }
  });

  test('a stale preview cannot publish drafts added after it was shown', async () => {
    const repo = await makeRepo();
    const provider = new FakeProvider(repo);
    const session = new ReviewSession(provider, repo.root, 'origin', memento(), 'github:octo/app');
    await session.open(1);
    await session.addDraft('src/app.ts', 5, 5, 'First');
    const revision = publishRevision(session.require());
    const panel = await showPanel(session);
    try {
      await session.addDraft('src/app.ts', 6, 6, 'New finding');
      await panel.onMessage({ type: 'publish', text: 'Summary', vote: 'comment', revision });
      assert.deepStrictEqual(provider.published, []);
      assert.strictEqual(session.require().drafts.all().length, 2);
    } finally { session.close(); session.dispose(); }
  });

  test('previews full draft bodies including suggestions, safely escaped', () => {
    const html = renderPublishHtml({ cspSource: '', nonce: 'test', title: 'PR', url: 'https://example.com', author: 'ana', descriptionHtml: '', summary: '', draftCount: 1,
      drafts: [{ id: 'd1', path: '<app>.ts', startLine: 5, endLine: 7, body: 'Fix this\n```suggestion\n<script>bad</script>\n```', headSha: 'abc', origin: 'manual' }], votes: ['comment'], vote: 'comment' });
    assert.ok(html.includes('&lt;app&gt;.ts:5-7'));
    assert.ok(html.includes('&lt;script&gt;bad&lt;/script&gt;'));
    assert.ok(html.includes('```suggestion'));
    assert.ok(html.includes('data-draft="d1"'));
    assert.ok(!html.includes('<script>bad</script>'));
  });

  test('exiting the review closes the publish panel', async () => {
    const repo = await makeRepo();
    const session = new ReviewSession(new FakeProvider(repo), repo.root, 'origin', memento(), 'github:octo/app');
    await session.open(1);
    await showPanel(session);
    session.close();
    assert.strictEqual(instance(), undefined);
    session.dispose();
  });

});
