'use client';

import React, { useEffect, useRef, useState } from 'react';
import { Bot, Loader2, Send, Sparkles } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { cn } from '@/lib/utils';
import { type ChatMessage, type ChatTurn, type ExtractedIncidentFields, incidentService } from '../services/incident-service';
import { IncidentError } from '../model/types';

interface Props {
  onReady: (fields: ExtractedIncidentFields) => void;
}

export function ChatIntake({ onReady }: Props) {
  const [messages, setMessages] = useState<ChatMessage[]>([
    { role: 'assistant', content: "Hi! I'm your IT helpdesk assistant. Describe what's going wrong and I'll help you file a ticket." },
  ]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [extracted, setExtracted] = useState<ExtractedIncidentFields | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const send = async () => {
    const text = input.trim();
    if (!text || sending) return;
    const userMsg: ChatMessage = { role: 'user', content: text };
    const next = [...messages, userMsg];
    setMessages(next);
    setInput('');
    setSending(true);
    try {
      // Send only the last 20 messages to keep the request size bounded
      const turn: ChatTurn = await incidentService.chat(next.slice(-20));
      setMessages([...next, { role: 'assistant', content: turn.reply }]);
      if (turn.extracted) setExtracted(turn.extracted);
    } catch (err) {
      const isConfigError = err instanceof IncidentError && err.message.toLowerCase().includes('not configured');
      const errorMsg = isConfigError
        ? 'AI chat is not configured on this server. Please use the form to report your incident.'
        : 'Sorry, I hit an error. Please try again or use the form instead.';
      setMessages([...next, { role: 'assistant', content: errorMsg }]);
    } finally {
      setSending(false);
    }
  };

  const handleKey = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
  };

  return (
    <div className="flex flex-col gap-3">
      {/* Message thread */}
      <div className="flex flex-col gap-3 max-h-96 overflow-y-auto pr-1">
        {messages.map((m, i) => (
          <div key={i} className={cn('flex gap-2.5', m.role === 'user' ? 'justify-end' : 'justify-start')}>
            {m.role === 'assistant' && (
              <span className="mt-1 flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/40 dark:text-blue-300">
                <Bot className="h-4 w-4" aria-hidden="true" />
              </span>
            )}
            <div className={cn(
              'max-w-[80%] rounded-2xl px-3.5 py-2.5 text-sm leading-relaxed',
              m.role === 'user'
                ? 'bg-primary text-primary-foreground rounded-br-sm'
                : 'bg-muted text-foreground rounded-bl-sm',
            )}>
              {m.content}
            </div>
          </div>
        ))}
        {sending && (
          <div className="flex gap-2.5 justify-start">
            <span className="mt-1 flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/40 dark:text-blue-300">
              <Bot className="h-4 w-4" aria-hidden="true" />
            </span>
            <div className="rounded-2xl rounded-bl-sm bg-muted px-3.5 py-2.5">
              <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" aria-label="Thinking…" />
            </div>
          </div>
        )}
        <div ref={bottomRef} />
      </div>

      {/* Pre-fill banner */}
      {extracted?.ready && (
        <div className="flex items-center justify-between gap-3 rounded-lg border border-green-200 bg-green-50 px-3.5 py-2.5 text-sm dark:border-green-800 dark:bg-green-950/30">
          <span className="flex items-center gap-1.5 font-medium text-green-800 dark:text-green-200">
            <Sparkles className="h-4 w-4 shrink-0" aria-hidden="true" />
            Form is ready to fill in
          </span>
          <Button size="sm" onClick={() => onReady(extracted)}>
            Pre-fill form
          </Button>
        </div>
      )}

      {/* Input */}
      <div className="flex gap-2">
        <Textarea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKey}
          placeholder="Describe your problem… (Enter to send)"
          rows={2}
          className="resize-none"
          disabled={sending}
          aria-label="Chat message"
        />
        <Button
          type="button"
          size="icon"
          onClick={send}
          disabled={!input.trim() || sending}
          aria-label="Send"
          className="self-end"
        >
          {sending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
        </Button>
      </div>
    </div>
  );
}
