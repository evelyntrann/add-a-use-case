// UC-STU-deactivate-student / BR-evaluation-inactive-report: the course section's peer evaluation report keeps a
// deactivated student's row, stored scores and comments, labeled by current account status, in an earlier week and in
// a later week with no evaluations. The API calls are stubbed; the backend's tests cover the report's contents.
// Run against the dev stack (backend + `npm run dev`) with `npm run cy:open`.

const ok = (data) => ({ flag: true, code: 200, message: 'OK', data })

const criteria = [1, 2].map((criterionId) => ({
  criterionId,
  criterion: `Criterion ${criterionId}`,
  description: `C${criterionId}`,
  maxScore: 10
}))

const average = (studentId, firstName, enabled, evaluated) => ({
  studentId,
  week: 'any',
  firstName,
  lastName: 'Student',
  email: `${firstName.toLowerCase()}@abc.edu`,
  teamName: 'Team1',
  enabled,
  averageTotalScore: evaluated ? 17.5 : 0,
  publicComments: evaluated ? [`${firstName} public`] : [],
  privateComments: evaluated ? [`${firstName} private`] : [],
  ratingAverages: evaluated
    ? criteria.map((c) => ({ criterionId: c.criterionId, averageScore: 8.75 }))
    : []
})

describe('Peer evaluation report account status', () => {
  let report

  beforeEach(() => {
    cy.login('b.gates@abc.edu', '123456')
    cy.intercept(
      'GET',
      '**/sections/*',
      ok({ sectionId: 2, sectionName: '2023-2024', rubricId: 1 })
    )
    cy.intercept('GET', '**/rubrics/*', ok({ rubricId: 1, rubricName: 'Rubric', criteria }))
    cy.intercept('GET', '**/sections/*/submission-status*', ok({ teams: [] }))
    cy.intercept('GET', '**/evaluations/sections/*/week/*', (req) => req.reply(ok(report))).as(
      'report'
    )
  })

  function rowFor(name) {
    return cy.contains('.el-table__body-wrapper .el-table__row', name)
  }

  it('labels an inactive student and keeps her stored scores and comments (earlier week)', () => {
    report = {
      sectionName: '2023-2024',
      week: '2023-W31',
      peerEvaluationAverages: [average(4, 'John', true, true), average(5, 'Eric', false, true)]
    }
    cy.visit('/section-evaluations')
    cy.wait('@report')

    cy.contains('.el-table__header', 'Current account status')
    rowFor('Student, John').find('[data-cy=account-status]').should('have.text', 'Active')
    rowFor('Student, Eric')
      .should('contain', 'Inactive')
      .and('contain', '17.50')
      .and('contain', 'Eric public')
      .and('contain', 'Eric private')
  })

  it('keeps the inactive row in a later week with no evaluations, without the no-students alert', () => {
    report = {
      sectionName: '2023-2024',
      week: '2023-W40',
      peerEvaluationAverages: [average(4, 'John', true, false), average(5, 'Eric', false, false)]
    }
    cy.visit('/section-evaluations')
    cy.wait('@report')

    rowFor('Student, Eric').should('contain', 'Inactive').and('contain', '0.00')
    cy.contains('No data is available.').should('not.exist')
  })
})
