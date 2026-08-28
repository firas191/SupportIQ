import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { TopicSnapshot } from '../models/topic.models';

/** Sujets emergents (S7-J1). Reserve aux responsables cote serveur. */
@Injectable({ providedIn: 'root' })
export class TopicsService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/api/topics`;

  /** Dernier instantane calcule. */
  latest(): Observable<TopicSnapshot> {
    return this.http.get<TopicSnapshot>(this.base);
  }

  /**
   * Recalcule immediatement.
   *
   * Le calcul tourne deja chaque nuit ; ce declenchement existe pour la demonstration et pour le
   * lendemain d'un import massif, ou attendre la nuit n'aurait pas de sens. Il peut prendre
   * plusieurs minutes — l'ecran doit le dire avant de le lancer, pas apres.
   */
  detect(): Observable<TopicSnapshot> {
    return this.http.post<TopicSnapshot>(`${this.base}/detect`, {});
  }
}
