export type TicketField =
  | 'externalRef'
  | 'customerEmail'
  | 'subject'
  | 'body'
  | 'createdAt'
  | 'language';

export interface RowError {
  line: number;
  message: string;
}

export interface ImportPreview {
  importId: number;
  filename: string;
  fileType: string;
  charset: string;
  status: string;
  totalRows: number;
  errorCount: number;
  headers: string[];
  preview: string[][];
  errors: RowError[];
}

export interface ConfirmResponse {
  importId: number;
  inserted: number;
  skipped: number;
  status: string;
}
