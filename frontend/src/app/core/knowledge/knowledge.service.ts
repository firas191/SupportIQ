import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { KbChunk, KbIngestResult, KbOverview } from '../models/kb.models';

/**
 * Base de connaissances (S5-J1).
 *
 * Le frontend ne parle **jamais** directement au service d'analyse : tout passe par Spring, qui
 * porte l'authentification et le controle des roles (rapport §6). C'est ce qui permet d'exposer une
 * indexation de documents sans exposer le service de calcul lui-meme.
 */
@Injectable({ providedIn: 'root' })
export class KnowledgeService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/api/kb`;

  overview(): Observable<KbOverview> {
    return this.http.get<KbOverview>(`${this.base}/documents`);
  }

  /** Indexe un document. Re-envoyer le meme nom de fichier remplace son contenu. */
  ingest(file: File): Observable<KbIngestResult> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.http.post<KbIngestResult>(`${this.base}/documents`, form);
  }

  delete(source: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/documents/${encodeURIComponent(source)}`);
  }

  /** Recalcule les vecteurs manquants (`force` recalcule tout — changement de modele). */
  reindex(force = false): Observable<{ processed: number }> {
    return this.http.post<{ processed: number }>(`${this.base}/reindex?force=${force}`, {});
  }

  /** Interroge la base : le « KB interrogeable » livre au J1. */
  search(question: string, k = 5): Observable<KbChunk[]> {
    return this.http.post<KbChunk[]>(`${this.base}/search`, { question, k });
  }
}
