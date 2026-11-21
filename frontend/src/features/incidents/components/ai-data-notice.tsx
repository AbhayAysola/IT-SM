import React from 'react';
import { Info } from 'lucide-react';

/**
 * Tells the reporter, in plain words, what leaves the system when AI assistance is on. The backend sends this text to Google's
 * Gemini API only when an administrator has configured a key, and the page cannot tell whether one is set, so the wording is
 * conditional. Keep it in step with "Google Gemini" in docs/architecture/security.md.
 */
export function AiDataNotice({ mode }: { mode: 'form' | 'chat' }) {
  return (
    <p data-testid="ai-data-notice" className="flex items-start gap-2 rounded-md border bg-muted/40 p-3 text-xs text-muted-foreground">
      <Info className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
      <span>
        <strong className="font-medium text-foreground">AI help and your data.</strong> When the administrators have switched on AI assistance,{' '}
        {mode === 'chat'
          ? 'what you type in this chat is sent to Google’s Gemini service to help fill in the form.'
          : 'the title and description you write here are sent to Google’s Gemini service when you submit, to suggest a category and spot duplicates. For the duplicate check, the titles and opening lines of other open incidents are sent too.'}{' '}
        Your name and email are not sent. Please do not include passwords or other secrets.
      </span>
    </p>
  );
}
