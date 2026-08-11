import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { InsightAnswer } from '../models/insight.models';

/**
 * Questions du chat Insight (S6-J3).
 *
 * **Chaque question part seule.** L'agent n'a pas de memoire de conversation
 * (decision du S6-J2 : pas de checkpointer, une question de manager est
 * instantanee et sans suite). L'interface affiche donc un *historique*, pas un
 * fil de discussion — et surtout elle ne suggere nulle part qu'une question de
 * relance (« et le mois dernier ? ») fonctionnerait. Promettre un
 * comportement qui n'existe pas est pire que ne pas l'offrir.
 */
@Injectable({ providedIn: 'root' })
export class InsightService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/api/insight`;

  ask(question: string): Observable<InsightAnswer> {
    return this.http.post<InsightAnswer>(`${this.base}/questions`, { question });
  }
}
