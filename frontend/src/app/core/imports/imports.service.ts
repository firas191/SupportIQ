import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ConfirmResponse, ImportPreview } from '../models/import.models';

/** Appels API de l'import structure : upload (multipart) et confirmation avec mapping. */
@Injectable({ providedIn: 'root' })
export class ImportsService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/api/imports`;

  upload(file: File): Observable<ImportPreview> {
    const form = new FormData();
    form.append('file', file);
    // HttpClient pose lui-meme le Content-Type multipart ; l'intercepteur ajoute juste le Bearer.
    return this.http.post<ImportPreview>(this.base, form);
  }

  confirm(importId: number, mapping: Record<string, string>): Observable<ConfirmResponse> {
    return this.http.post<ConfirmResponse>(`${this.base}/${importId}/confirm`, { mapping });
  }
}
