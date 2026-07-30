import { Injectable, signal } from '@angular/core';
import { Client, IMessage } from '@stomp/stompjs';

/** Evenement pousse par le backend (miroir de RealtimeEvent). */
export interface RealtimeEvent {
  type: 'TICKET_CREATED' | 'TICKET_ANALYZED';
  ticketId: number | null;
  externalRef: string | null;
  subject: string | null;
  category: string | null;
  priority: string | null;
  sentiment: string | null;
}

/**
 * Canal temps reel STOMP (S4-J5, rapport §6 : topics /topic/tickets et /topic/alerts).
 *
 * Les messages sont des **signaux** : on expose le dernier evenement et des compteurs en signals,
 * et c'est aux ecrans de decider s'ils rechargent leurs donnees via l'API REST. Ce choix evite de
 * dupliquer les regles de securite dans le canal WebSocket.
 *
 * Reconnexion automatique geree par stompjs (`reconnectDelay`).
 */
@Injectable({ providedIn: 'root' })
export class RealtimeService {
  private client?: Client;

  /** Connexion active (affiche le badge « live »). */
  readonly connected = signal(false);
  /** Dernier evenement recu (les ecrans y reagissent). */
  readonly lastEvent = signal<RealtimeEvent | null>(null);
  /** Nombre de tickets arrives depuis le dernier rafraichissement de l'ecran. */
  readonly newTickets = signal(0);
  /** Nombre d'analyses terminees depuis le dernier rafraichissement. */
  readonly newAnalyses = signal(0);

  connect(): void {
    if (this.client?.active) {
      return;
    }
    // En dev, le proxy Angular ne relaie pas le WebSocket : on cible directement le backend.
    const url = this.buildUrl();

    this.client = new Client({
      brokerURL: url,
      reconnectDelay: 5000,
      onConnect: () => {
        this.connected.set(true);
        this.client?.subscribe('/topic/tickets', (msg: IMessage) => this.onTicketEvent(msg));
      },
      onWebSocketClose: () => this.connected.set(false),
      onStompError: () => this.connected.set(false),
    });
    this.client.activate();
  }

  disconnect(): void {
    this.client?.deactivate();
    this.connected.set(false);
  }

  /** Remet les compteurs a zero (appele quand l'ecran vient de recharger ses donnees). */
  acknowledge(): void {
    this.newTickets.set(0);
    this.newAnalyses.set(0);
  }

  private onTicketEvent(msg: IMessage): void {
    try {
      const event = JSON.parse(msg.body) as RealtimeEvent;
      this.lastEvent.set(event);
      if (event.type === 'TICKET_CREATED') {
        this.newTickets.update((n) => n + 1);
      } else if (event.type === 'TICKET_ANALYZED') {
        this.newAnalyses.update((n) => n + 1);
      }
    } catch {
      // message illisible : on ignore (le canal n'est qu'un signal)
    }
  }

  private buildUrl(): string {
    const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws';
    // apiBaseUrl vide en dev => on vise le backend sur 8080 ; en prod, meme origine derriere nginx.
    const host = environmentHost();
    return `${scheme}://${host}/ws`;
  }
}

/** Hote du backend : localhost:8080 en dev, origine courante en prod. */
function environmentHost(): string {
  const { hostname, host } = window.location;
  return host.includes('4200') ? `${hostname}:8080` : host;
}
